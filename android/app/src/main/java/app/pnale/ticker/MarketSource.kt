package app.pnale.ticker

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.WebSocket
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

sealed class StreamEvent {
    data class Ticker(val quote: Quote) : StreamEvent()
    data class Kline(val symbol: String, val interval: String, val candle: Candle, val eventTime: Long) : StreamEvent()
    object Shutdown : StreamEvent()
    /** Keep-alive reply: proves the socket is open but carries no price. */
    object Alive : StreamEvent()
}

/** Exchange-specific REST / WS formats. The engine always keys quotes as "<COIN>USDT". */
interface MarketSource {
    val id: String
    val label: String
    /**
     * Per-coin listing check. Full contract lists are hundreds of KB, which time out on throttled
     * mainland links (measured ~20 KB/s); a single-coin lookup is about 1 KB.
     */
    fun lookupRequest(coin: String): Pair<String, Map<String, String>>
    /** true = listed, false = not listed; throws when the response says neither (e.g. rate limit). */
    fun listed(code: Int, body: String): Boolean
    fun candlesRequest(coin: String, interval: String): Pair<String, Map<String, String>>
    fun candles(body: String): List<Candle>
    /** A single-coin ticker request when the list is short, otherwise all tickers. */
    fun quotesRequest(coin: String?): Pair<String, Map<String, String>>
    fun quotes(body: String, coins: List<String>, receivedAt: Long): List<Quote>
    fun streamUrl(route: MarketRoute, coins: List<String>, selected: String, interval: String): String
    fun subscribe(socket: WebSocket, coins: List<String>, selected: String, interval: String) {}
    fun keepAlive(socket: WebSocket) {}
    fun parse(text: String): List<StreamEvent>
    /** Sources without a usable push stream are polled over REST instead. */
    val restOnly: Boolean get() = false
    val pollMs: Long get() = 5_000

    companion object {
        // A getter, not a field: the sources implement this interface, so eager init would see them as null.
        val all get() = listOf(BinanceSource, GateSource, HtxSource)
        /** Automatic mode tries mainland-reachable exchanges first; Binance (via relay) is the backup, HTX the last resort. */
        val autoChain get() = listOf(GateSource, BinanceSource, HtxSource)
        fun of(id: String) = all.firstOrNull { it.id == id } ?: BinanceSource
        /** The source to use for a saved preference; "auto" starts from the one that last worked. */
        fun resolve(preference: String, lastGood: String) = if (preference == "auto") autoChain.firstOrNull { it.id == lastGood } ?: GateSource else of(preference)
    }
}

object BinanceSource : MarketSource {
    override val id = "binance"
    override val label = "币安"
    override fun lookupRequest(coin: String) = "ticker/24hr" to mapOf("symbol" to coin + "USDT")
    // Through the relay an unknown symbol arrives as 502 "upstream HTTP 400".
    override fun listed(code: Int, body: String) = when {
        code == 200 -> true
        code == 400 || (code == 502 && body.contains("HTTP 400")) -> false
        else -> throw java.io.IOException("合约查询 HTTP $code")
    }
    override fun candlesRequest(coin: String, interval: String) = "klines" to mapOf("symbol" to coin + "USDT", "interval" to interval, "limit" to "60")
    override fun candles(body: String): List<Candle> = JSONArray(body).let { json -> (0 until json.length()).map { Candle.parse(json.getJSONArray(it)) } }
    override fun quotesRequest(coin: String?) = "ticker/24hr" to (coin?.let { mapOf("symbol" to it + "USDT") } ?: emptyMap())
    override fun quotes(body: String, coins: List<String>, receivedAt: Long): List<Quote> {
        val rows = if (body.trimStart().startsWith("[")) JSONArray(body).let { a -> (0 until a.length()).map { a.getJSONObject(it) } } else listOf(JSONObject(body))
        return rows.filter { it.optString("symbol").removeSuffix("USDT") in coins }.map { Quote.parse(it) }
    }
    override fun streamUrl(route: MarketRoute, coins: List<String>, selected: String, interval: String): String {
        val streams = coins.map { "${it.lowercase(Locale.ROOT)}usdt@ticker" } + "${selected.lowercase(Locale.ROOT)}usdt@kline_$interval"
        return route.ws.toHttpUrl().newBuilder().addQueryParameter("streams", streams.joinToString("/")).build().toString()
    }
    override fun parse(text: String): List<StreamEvent> {
        val payload = JSONObject(text).getJSONObject("data")
        return when (payload.optString("e")) {
            "24hrTicker" -> listOf(StreamEvent.Ticker(Quote.parse(payload, true)))
            "kline" -> payload.getJSONObject("k").let { listOf(StreamEvent.Kline(it.optString("s"), it.optString("i"), Candle.parse(it), payload.getLong("E"))) }
            "serverShutdown" -> listOf(StreamEvent.Shutdown)
            else -> emptyList()
        }
    }
}

/** Gate.io USDT perpetuals: reachable from mainland networks without a proxy. Prices are Gate's own, not Binance's. */
object GateSource : MarketSource {
    override val id = "gate"
    override val label = "Gate"
    private fun contract(coin: String) = coin + "_USDT"
    override fun lookupRequest(coin: String) = "contracts/${contract(coin)}" to emptyMap<String, String>()
    override fun listed(code: Int, body: String) = when (code) {
        200 -> !JSONObject(body).optBoolean("in_delisting", false)
        400, 404 -> false
        else -> throw java.io.IOException("合约查询 HTTP $code")
    }
    private fun symbol(contract: String) = contract.replace("_", "")
    // Gate mixes JSON numbers and numeric strings; accept both.
    private fun num(j: JSONObject, key: String) = finite(j.get(key).toString())
    override fun candlesRequest(coin: String, interval: String) = "candlesticks" to mapOf("contract" to contract(coin), "interval" to interval, "limit" to "60")
    private fun candle(j: JSONObject) = Candle(j.getLong("t") * 1000, num(j, "o"), num(j, "h"), num(j, "l"), num(j, "c"), num(j, "v"))
    override fun candles(body: String): List<Candle> = JSONArray(body).let { a -> (0 until a.length()).map { candle(a.getJSONObject(it)) }.sortedBy { it.time } }
    override fun quotesRequest(coin: String?) = "tickers" to (coin?.let { mapOf("contract" to contract(it)) } ?: emptyMap())
    private fun quote(j: JSONObject, eventTime: Long): Quote {
        val price = num(j, "last")
        val quote = Quote(symbol(j.getString("contract")), price, num(j, "change_percentage"),
            j.optString("high_24h").toDoubleOrNull() ?: price, j.optString("low_24h").toDoubleOrNull() ?: price,
            j.optString("volume_24h_quote").toDoubleOrNull() ?: 0.0, eventTime)
        require(quote.price > 0) { "报价数据异常" }
        return quote
    }
    // REST tickers carry no exchange timestamp, so HTTP samples use the receive time (and never drive alarms).
    override fun quotes(body: String, coins: List<String>, receivedAt: Long): List<Quote> =
        JSONArray(body).let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
            .filter { it.optString("contract").removeSuffix("_USDT") in coins }.map { quote(it, receivedAt) }
    override fun streamUrl(route: MarketRoute, coins: List<String>, selected: String, interval: String) = route.ws
    private fun send(socket: WebSocket, channel: String, event: String? = null, payload: List<String>? = null) {
        socket.send(JSONObject().put("time", System.currentTimeMillis() / 1000).put("channel", channel).apply {
            if (event != null) put("event", event)
            if (payload != null) put("payload", JSONArray(payload))
        }.toString())
    }
    override fun subscribe(socket: WebSocket, coins: List<String>, selected: String, interval: String) {
        send(socket, "futures.tickers", "subscribe", coins.map(::contract))
        send(socket, "futures.candlesticks", "subscribe", listOf(interval, contract(selected)))
    }
    override fun keepAlive(socket: WebSocket) = send(socket, "futures.ping")
    override fun parse(text: String): List<StreamEvent> {
        val message = JSONObject(text)
        val channel = message.optString("channel"); val event = message.optString("event")
        message.optJSONObject("error")?.let { throw java.io.IOException("Gate：${it.optString("message", "订阅失败")}") }
        if (channel == "futures.pong" || event == "subscribe") return listOf(StreamEvent.Alive)
        if (event != "update") return emptyList()
        val time = message.getLong("time_ms")
        val rows = message.getJSONArray("result").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
        return when (channel) {
            "futures.tickers" -> rows.map { StreamEvent.Ticker(quote(it, time)) }
            "futures.candlesticks" -> rows.map {
                val name = it.getString("n") // e.g. 1m_ETH_USDT
                StreamEvent.Kline(symbol(name.substringAfter("_")), name.substringBefore("_"), candle(it), time)
            }
            else -> emptyList()
        }
    }
}

/**
 * HTX USDT perpetuals via api.hbdm.vn (reachable from mainland networks). Its WS pushes are sparse on thin books,
 * so it is polled over REST; each response's server timestamp is the snapshot time.
 */
object HtxSource : MarketSource {
    override val id = "htx"
    override val label = "HTX"
    override val restOnly get() = true
    override val pollMs get() = 3_000L
    private fun contract(coin: String) = "$coin-USDT"
    private fun num(j: JSONObject, key: String) = finite(j.get(key).toString())
    override fun lookupRequest(coin: String) = "linear-swap-api/v1/swap_contract_info" to mapOf("contract_code" to contract(coin))
    // HTX answers 200 with status=error / err_code 1014 for an unknown contract.
    override fun listed(code: Int, body: String): Boolean {
        if (code != 200) throw java.io.IOException("合约查询 HTTP $code")
        val root = JSONObject(body)
        if (root.optString("status") != "ok") { if (root.optInt("err_code") == 1014) return false; throw java.io.IOException("HTX：${root.optString("err_msg")}") }
        val data = root.optJSONArray("data") ?: return false
        return (0 until data.length()).any { data.getJSONObject(it).optInt("contract_status") == 1 }
    }
    private val periods = mapOf("1m" to "1min", "5m" to "5min", "15m" to "15min", "1h" to "60min")
    override fun candlesRequest(coin: String, interval: String) = "linear-swap-ex/market/history/kline" to mapOf("contract_code" to contract(coin), "period" to periods.getValue(interval), "size" to "60")
    override fun candles(body: String): List<Candle> = JSONObject(body).getJSONArray("data").let { a ->
        (0 until a.length()).map { a.getJSONObject(it) }.map { Candle(it.getLong("id") * 1000, num(it, "open"), num(it, "high"), num(it, "low"), num(it, "close"), num(it, "vol")) }.sortedBy { it.time }
    }
    override fun quotesRequest(coin: String?) = if (coin != null) "linear-swap-ex/market/detail/merged" to mapOf("contract_code" to contract(coin))
        else "linear-swap-ex/market/detail/batch_merged" to mapOf("business_type" to "swap")
    private fun quote(t: JSONObject, symbol: String, time: Long): Quote {
        val open = num(t, "open"); val close = num(t, "close")
        // merged ticks are rolling 24h, so change is measured against the 24h open.
        return Quote(symbol, close, if (open > 0) (close / open - 1) * 100 else 0.0, num(t, "high"), num(t, "low"), t.optString("trade_turnover").toDoubleOrNull() ?: 0.0, time)
            .also { require(it.price > 0) { "报价数据异常" } }
    }
    override fun quotes(body: String, coins: List<String>, receivedAt: Long): List<Quote> {
        val root = JSONObject(body)
        require(root.optString("status") == "ok") { "HTX：${root.optString("err_msg", "请求失败")}" }
        val time = root.optLong("ts", receivedAt)
        root.optJSONObject("tick")?.let { t ->
            val coin = root.optString("ch").split(".").getOrNull(1)?.removeSuffix("-USDT") ?: coins.single()
            return if (coin in coins) listOf(quote(t, coin + "USDT", time)) else emptyList()
        }
        val ticks = root.getJSONArray("ticks")
        return (0 until ticks.length()).map { ticks.getJSONObject(it) }.filter { it.optString("contract_code").removeSuffix("-USDT") in coins }
            .map { quote(it, it.getString("contract_code").replace("-", ""), it.optLong("ts", time)) }
    }
    override fun streamUrl(route: MarketRoute, coins: List<String>, selected: String, interval: String) = route.ws
    override fun parse(text: String): List<StreamEvent> = emptyList()
}
