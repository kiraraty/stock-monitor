package app.pnale.ticker

import org.json.JSONArray
import org.json.JSONObject
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

data class Quote(val symbol: String, val price: Double, val change: Double,
    val high: Double, val low: Double, val volume: Double, val eventTime: Long) {
    fun stale(now: Long = System.currentTimeMillis()) = now - eventTime > 15_000 || eventTime > now + 5_000
    companion object {
        fun parse(json: JSONObject, stream: Boolean = false): Quote {
            fun value(rest: String, ws: String) = finite(json.getString(if (stream) ws else rest))
            val quote = Quote(json.getString(if (stream) "s" else "symbol"), value("lastPrice", "c"),
                value("priceChangePercent", "P"), value("highPrice", "h"), value("lowPrice", "l"),
                value("quoteVolume", "q"), json.getLong(if (stream) "E" else "closeTime"))
            require(quote.price > 0 && quote.eventTime > 0) { "报价数据异常" }
            return quote
        }
    }
}

data class Candle(val time: Long, val open: Double, val high: Double, val low: Double, val close: Double, val volume: Double) {
    init {
        require(time >= 0 && listOf(open, high, low, close, volume).all { it.isFinite() })
        require(low > 0 && high >= maxOf(open, close) && low <= minOf(open, close) && volume >= 0)
    }
    companion object {
        fun parse(row: JSONArray) = Candle(row.getLong(0), finite(row.getString(1)), finite(row.getString(2)),
            finite(row.getString(3)), finite(row.getString(4)), finite(row.getString(5)))
        fun parse(json: JSONObject) = Candle(json.getLong("t"), finite(json.getString("o")), finite(json.getString("h")),
            finite(json.getString("l")), finite(json.getString("c")), finite(json.getString("v")))
        fun merge(previous: List<Candle>, incoming: List<Candle>) =
            (previous + incoming).associateBy { it.time }.values.sortedBy { it.time }.takeLast(60)
    }
}

fun finite(raw: String): Double = raw.toDouble().also { require(it.isFinite()) { "行情数值异常" } }

object WatchlistInput {
    val defaults = listOf("ETH", "BTC")
    fun parse(text: String): List<String> {
        val coins = text.uppercase(Locale.ROOT).split(Regex("[\\s,，、;；]+"))
            .filter { it.isNotEmpty() }.map { it.removeSuffix("/USDT").removeSuffix("USDT") }.distinct()
        require(coins.isNotEmpty() && coins.none { it.isEmpty() }) { "请至少输入一个币种，例如 ETH, BTC" }
        require(coins.size <= 30) { "最多同时展示 30 个币种" }
        return coins
    }
    fun validate(text: String, catalog: Set<String>): List<String> {
        val coins = parse(text)
        val invalid = coins.filter { it !in catalog }
        require(invalid.isEmpty()) { "未找到 U 本位永续合约：${invalid.joinToString("、")}" }
        return coins
    }
    fun catalog(json: JSONObject): Set<String> {
        val list = json.getJSONArray("symbols")
        return (0 until list.length()).map { list.getJSONObject(it) }
            .filter { it.optString("contractType") in setOf("PERPETUAL", "TRADIFI_PERPETUAL") && it.optString("status") == "TRADING" &&
                it.optString("quoteAsset") == "USDT" && it.optString("marginAsset") == "USDT" && it.optString("symbol").endsWith("USDT") }
            .map { it.getString("symbol").removeSuffix("USDT") }.toSet()
            .also { require(it.isNotEmpty()) { "合约列表暂不可用" } }
    }
}

object Prices {
    fun format(value: Double): String {
        val decimals = when { value >= 1000 -> 2; value >= 1 -> 3; value >= .01 -> 4; value >= .0001 -> 6; else -> 8 }
        return DecimalFormat("#,##0." + "0".repeat(decimals), DecimalFormatSymbols(Locale.US)).format(value)
    }
    fun change(value: Double) = String.format(Locale.US, "%+.2f%%", value)
    fun volume(value: Double) = when {
        value >= 1e9 -> String.format(Locale.US, "%.2fB", value / 1e9)
        value >= 1e6 -> String.format(Locale.US, "%.1fM", value / 1e6)
        else -> String.format(Locale.US, "%.1fK", value / 1e3)
    }
}

data class MarketState(val coins: List<String>, val selected: String, val interval: String,
    val quotes: Map<String, Quote> = emptyMap(), val candles: List<Candle> = emptyList(),
    val status: String = "连接中", val error: String? = null, val chartEventTime: Long = 0,
    val streamedSymbols: Set<String> = emptySet(), val streamedCandle: Boolean = false, val route: String = "direct") {
    val quote get() = quotes[selected + "USDT"]
    fun connectionLabel() = "${when (route) { "relay" -> "新加坡中继"; "gate" -> "Gate 直连"; "htx" -> "HTX 直连"; else -> "币安直连" }} · ${displayStatus()}"
    fun displayStatus(now: Long = System.currentTimeMillis()) = if (quote?.stale(now) == true) "行情已过期" else status
    fun chartStale(now: Long = System.currentTimeMillis()) = now - chartEventTime > 15_000
    val intervalMillis get() = when (interval) { "5m" -> 300_000L; "15m" -> 900_000L; "1h" -> 3_600_000L; else -> 60_000L }
}
