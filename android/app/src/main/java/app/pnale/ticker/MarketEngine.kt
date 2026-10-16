package app.pnale.ticker

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** One owner-counted connection. REST timestamps remain exchange timestamps, including via the relay. */
class MarketEngine(private val preferences: Preferences, context: Context? = null, private val transport: MarketTransport = MarketTransport(context)) {
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newFixedThreadPool(3)
    private var source: MarketSource = MarketSource.resolve(preferences.marketSource, preferences.lastGoodSource)
    private var route = transport.initial(preferences.networkMode, source.id, preferences.lastGoodRoute)
    private val connectivity = context?.getSystemService(ConnectivityManager::class.java)
    private var networkRegistered = false
    private var networkSeen = false
    private var currentNetwork: Network? = null
    private val requests = ConcurrentHashMap.newKeySet<Call>()
    private val owners = mutableSetOf<Any>()
    private val listeners = mutableSetOf<(MarketState) -> Unit>()
    @Volatile private var generation = 0L
    private var socket: WebSocket? = null
    private var socketSequence = 0L
    private var retry: Runnable? = null
    private var poll: Runnable? = null
    private var failures = 0
    private var socketFailures = 0
    private var lastMessage = 0L
    private var ticks = 0L
    private var snapshotLoading = false
    private var connectAfterSnapshot = false
    // Small per-coin requests run side by side; large list endpoints are too slow on throttled links.
    private val fetchPool = Executors.newFixedThreadPool(6)
    private val listedCache = ConcurrentHashMap<String, Pair<Long, Boolean>>()
    var state = MarketState(preferences.coins, preferences.selected, preferences.interval)
        private set

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { main.post {
            val changed = networkSeen && currentNetwork != network
            networkSeen = true; currentNetwork = network
            if (changed && owners.isNotEmpty()) restart()
        } }
        override fun onLost(network: Network) { main.post {
            if (network == currentNetwork && owners.isNotEmpty()) {
                currentNetwork = null; cancelConnection()
                state = state.copy(status = "等待网络", error = "网络已断开，连接恢复后自动重连"); emit()
            }
        } }
    }
    private val tick = object : Runnable {
        override fun run() {
            if (owners.isEmpty()) return
            if (socket != null && SystemClock.elapsedRealtime() - lastMessage > 15_000) socketFailed(generation, socketSequence, "行情推送超时")
            if (++ticks % 10 == 0L) socket?.let { source.keepAlive(it) }
            emit(); main.postDelayed(this, 1000)
        }
    }
    fun listen(listener: (MarketState) -> Unit) { listeners.add(listener); listener(state) }
    fun unlisten(listener: (MarketState) -> Unit) { listeners.remove(listener) }
    fun acquire(owner: Any) {
        if (owners.add(owner) && owners.size == 1) {
            if (!networkRegistered && connectivity != null) runCatching { connectivity?.registerDefaultNetworkCallback(networkCallback); networkRegistered = true }
            main.post(tick); restart()
        }
    }
    fun release(owner: Any) {
        owners.remove(owner)
        if (owners.isEmpty()) {
            cancelConnection(); main.removeCallbacks(tick)
            if (networkRegistered) runCatching { connectivity?.unregisterNetworkCallback(networkCallback) }
            networkRegistered = false; networkSeen = false; currentNetwork = null
            state = state.copy(status = "已暂停"); emit()
        }
    }
    fun reloadPreferences() {
        source = MarketSource.resolve(preferences.marketSource, preferences.lastGoodSource)
        state = MarketState(preferences.coins, preferences.selected, preferences.interval)
        restart(clearChart = true)
    }
    fun select(coin: String) {
        if (coin !in state.coins || coin == state.selected) return
        preferences.selected = coin; state = state.copy(selected = coin); switchChart()
    }
    fun interval(value: String) {
        if (value !in listOf("1m", "5m", "15m", "1h") || value == state.interval) return
        preferences.interval = value; state = state.copy(interval = value); switchChart()
    }
    /** Blocking: the coins [target] does not list. Each coin is looked up on its own (cached ten minutes). */
    private fun missing(target: MarketSource, coins: List<String>, preferred: MarketRoute): List<String> {
        val start = if (target.id == source.id) preferred else transport.initial(preferences.networkMode, target.id, preferences.lastGoodRoute)
        val candidates = if (preferences.networkMode == "auto") listOf(start, transport.alternate(start)).distinctBy { it.id } else listOf(start)
        fun probe(coin: String): Boolean {
            listedCache["${target.id}:$coin"]?.takeIf { System.currentTimeMillis() - it.first < 600_000 }?.let { return it.second }
            var error: Exception? = null
            for (candidate in candidates) try {
                val (path, query) = target.lookupRequest(coin)
                val url = "${candidate.rest}/$path".toHttpUrl().newBuilder().apply { query.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
                val listed = candidate.client.newCall(Request.Builder().url(url).build()).execute().use { r -> target.listed(r.code, r.body?.string().orEmpty()) }
                listedCache["${target.id}:$coin"] = System.currentTimeMillis() to listed
                return listed
            } catch (e: Exception) { error = e }
            throw error ?: IOException("合约查询不可用")
        }
        val results = coins.map { coin -> coin to fetchPool.submit<Boolean> { probe(coin) } }
        return results.filter { (_, future) ->
            try { !future.get() } catch (e: java.util.concurrent.ExecutionException) { throw (e.cause as? Exception) ?: e }
        }.map { it.first }
    }
    /** Switches exchange; the callback receives watchlist coins the new source does not list, or an error. */
    fun switchSource(id: String, callback: (List<String>, String?) -> Unit) {
        val target = MarketSource.resolve(id, preferences.lastGoodSource)
        if (target.id == source.id) { preferences.marketSource = id; callback(emptyList(), null); return }
        val preferred = route
        io.execute {
            val missing = try { missing(target, state.coins, preferred) } catch (e: Exception) { main.post { callback(emptyList(), e.message ?: "合约列表不可用") }; return@execute }
            main.post {
                preferences.marketSource = id; source = target
                val selected = state.selected.takeIf { it !in missing } ?: state.coins.firstOrNull { it !in missing } ?: state.selected
                preferences.selected = selected
                state = state.copy(selected = selected, quotes = emptyMap())
                restart(clearChart = true); callback(missing, null)
            }
        }
    }
    fun saveInput(text: String, accept: () -> Boolean = { true }, callback: (String?) -> Unit) {
        try { WatchlistInput.parse(text) } catch (e: Exception) { callback(e.message); return }
        val preferred = route; val target = source
        io.execute {
            try {
                val coins = WatchlistInput.parse(text)
                val absent = missing(target, coins, preferred)
                require(absent.isEmpty()) { "${target.label}没有这些 USDT 永续合约：${absent.joinToString("、")}" }
                main.post {
                    if (!accept()) { callback("内容已更新，忽略旧校验结果"); return@post }
                    val selected = state.selected.takeIf { it in coins } ?: coins.first()
                    val changed = selected != state.selected
                    preferences.coins = coins; preferences.selected = selected
                    state = state.copy(coins = coins, selected = selected, quotes = state.quotes.filterKeys { it.removeSuffix("USDT") in coins })
                    restart(clearChart = changed); callback(null)
                }
            } catch (e: Exception) { main.post { callback(e.message ?: "校验失败，请检查连接") } }
        }
    }
    fun restart(clearChart: Boolean = false, resetBackoff: Boolean = true) {
        cancelConnection()
        if (resetBackoff) { failures = 0; socketFailures = 0; route = transport.initial(preferences.networkMode, source.id, preferences.lastGoodRoute) }
        state = state.copy(status = if (owners.isEmpty()) "已暂停" else "连接中", error = null, route = route.id,
            candles = if (clearChart) emptyList() else state.candles, chartEventTime = if (clearChart) 0 else state.chartEventTime,
            streamedSymbols = emptySet(), streamedCandle = false)
        emit()
        if (owners.isNotEmpty()) snapshot(generation, initial = true)
    }
    private fun snapshot(token: Long, initial: Boolean = false) {
        if (token != generation || owners.isEmpty()) return
        if (initial) connectAfterSnapshot = true
        if (snapshotLoading) return
        snapshotLoading = true
        val requested = state; val connection = route; val src = source
        io.execute {
            try {
                val candles = src.candlesRequest(requested.selected, requested.interval).let { (path, query) -> src.candles(get(connection, path, query, token)) }
                require(candles.size >= 2 && candles.zipWithNext().all { (a, b) -> b.time - a.time == requested.intervalMillis }) { "K 线不连续，需要重新回填" }
                // One small request per coin, in parallel; a coin that fails keeps its previous quote.
                val quotes = requested.coins.map { coin ->
                    fetchPool.submit<List<Quote>> { src.quotesRequest(coin).let { (path, query) -> src.quotes(get(connection, path, query, token), listOf(coin), System.currentTimeMillis()) } }
                }.flatMap { runCatching { it.get() }.getOrDefault(emptyList()) }
                if (quotes.isEmpty() && requested.coins.isNotEmpty()) throw IOException("报价请求全部失败")
                main.post {
                    if (token != generation || owners.isEmpty()) return@post
                    snapshotLoading = false
                    if (connection.id in listOf("direct", "relay") && preferences.lastGoodRoute != connection.id) preferences.lastGoodRoute = connection.id
                    if (preferences.marketSource == "auto" && preferences.lastGoodSource != src.id) preferences.lastGoodSource = src.id
                    if (state.status != "实时推送") {
                        val now = System.currentTimeMillis()
                        state = state.copy(candles = candles, quotes = state.quotes + quotes.associateBy { it.symbol },
                            chartEventTime = if (now - candles.last().time < state.intervalMillis + 15_000) now else 0,
                            status = if (src.restOnly) "HTTP 轮询" else if (preferences.restFallback) "HTTP 兜底" else "快照 · 连接推送中")
                        emit()
                    }
                    if (connectAfterSnapshot) { connectAfterSnapshot = false; if (!src.restOnly) connect(token) }
                    schedulePoll(token)
                }
            } catch (e: Exception) { main.post {
                if (token != generation || owners.isEmpty()) return@post
                snapshotLoading = false
                if (initial || socket == null) failed(token, e.message ?: "网络不可用")
                else { state = state.copy(error = e.message); emit(); schedulePoll(token) }
            } }
        }
    }
    private fun schedulePoll(token: Long) {
        poll?.let(main::removeCallbacks); poll = null
        if (owners.isEmpty()) return
        if (source.restOnly) { poll = Runnable { snapshot(token) }.also { main.postDelayed(it, source.pollMs) }; return }
        if (!preferences.restFallback || state.status == "实时推送") return
        poll = Runnable { snapshot(token) }.also { main.postDelayed(it, 5000) }
    }
    private fun get(connection: MarketRoute, path: String, query: Map<String, String>, token: Long): String {
        if (token != generation) throw IOException("已取消")
        val builder = "${connection.rest}/$path".toHttpUrl().newBuilder()
        query.forEach { (k, v) -> builder.addQueryParameter(k, v) }
        val call = connection.client.newCall(Request.Builder().url(builder.build()).header("Cache-Control", "no-cache").build())
        requests.add(call)
        try {
            if (token != generation) { call.cancel(); throw IOException("已取消") }
            return call.execute().use { r -> if (!r.isSuccessful) throw IOException("行情 HTTP ${r.code}"); r.body?.string() ?: throw IOException("行情为空") }
        } finally { requests.remove(call) }
    }
    /**
     * Changing the chart must not interrupt the quote stream: load the new candles over REST, open a new socket,
     * and keep the old one delivering quotes until the new socket's first message arrives (make before break).
     */
    private var chartRequest = 0L
    private fun switchChart() {
        if (owners.isEmpty() || socket == null || source.restOnly || state.status != "实时推送") { restart(clearChart = true); return }
        val token = generation; val request = ++chartRequest
        val wanted = state.selected to state.interval
        state = state.copy(candles = emptyList(), chartEventTime = 0, streamedCandle = false); emit()
        val src = source; val connection = route
        io.execute {
            val candles = runCatching {
                src.candlesRequest(wanted.first, wanted.second).let { (path, query) -> src.candles(get(connection, path, query, token)) }
                    .also { c -> require(c.size >= 2 && c.zipWithNext().all { (a, b) -> b.time - a.time == state.intervalMillis }) }
            }.getOrNull()
            main.post {
                // A newer switch or a full restart supersedes this one.
                if (token != generation || request != chartRequest || wanted != (state.selected to state.interval)) return@post
                if (candles == null) { restart(clearChart = true); return@post }
                val now = System.currentTimeMillis()
                state = state.copy(candles = candles, chartEventTime = if (now - candles.last().time < state.intervalMillis + 15_000) now else 0); emit()
                handoff?.first?.cancel()
                handoff = socket?.let { it to socketSequence }
                socket = null
                connect(token)
            }
        }
    }
    /** The previous socket during a chart switch, with its attempt number; it only keeps quotes flowing. */
    private var handoff: Pair<WebSocket, Long>? = null
    private fun connect(token: Long) {
        if (token != generation || owners.isEmpty()) return
        val attempt = ++socketSequence
        val src = source; val coins = state.coins; val selected = state.selected; val interval = state.interval
        val url = src.streamUrl(route, coins, selected, interval)
        lastMessage = SystemClock.elapsedRealtime()
        socket = route.client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { src.subscribe(webSocket, coins, selected, interval) }
            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val events = src.parse(text)
                    main.post {
                        if (token != generation) return@post
                        val old = handoff
                        if (old != null && attempt == old.second) {
                            // Old socket during a handoff: quotes only; its failure just ends the handoff.
                            runCatching { receive(events.filterIsInstance<StreamEvent.Ticker>()) }.onFailure { old.first.cancel(); handoff = null }
                            return@post
                        }
                        if (attempt != socketSequence) return@post
                        if (old != null) { old.first.cancel(); handoff = null }
                        try { receive(events) } catch (e: Exception) { socketFailed(token, attempt, e.message ?: "行情格式异常") }
                    }
                } catch (e: Exception) { main.post { socketFailed(token, attempt, e.message ?: "行情格式异常") } }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { main.post { socketFailed(token, attempt, t.message ?: "推送断开") } }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason); main.post { socketFailed(token, attempt, "推送已关闭") } }
        })
    }
    private fun receive(events: List<StreamEvent>) {
        var priced = false
        for (event in events) when (event) {
            is StreamEvent.Alive -> lastMessage = SystemClock.elapsedRealtime()
            is StreamEvent.Shutdown -> throw IOException("行情服务重启")
            is StreamEvent.Ticker -> {
                val quote = event.quote
                if (quote.symbol.removeSuffix("USDT") !in state.coins) continue
                require(!quote.stale() && quote.eventTime <= System.currentTimeMillis() + 5000) { "收到过期报价" }
                if (quote.eventTime >= (state.quotes[quote.symbol]?.eventTime ?: 0)) state = state.copy(quotes = state.quotes + (quote.symbol to quote), streamedSymbols = state.streamedSymbols + quote.symbol)
                priced = true
            }
            is StreamEvent.Kline -> {
                if (event.symbol != state.selected + "USDT" || event.interval != state.interval) continue
                val candle = event.candle; val previous = state.candles.lastOrNull() ?: throw IOException("需要回填 K 线")
                if (candle.time > previous.time + state.intervalMillis) throw IOException("K 线有缺口，正在回填")
                if (event.eventTime < state.chartEventTime) continue
                state = state.copy(candles = Candle.merge(state.candles, listOf(candle)), chartEventTime = event.eventTime, streamedCandle = true)
                priced = true
            }
        }
        if (!priced) return
        lastMessage = SystemClock.elapsedRealtime()
        state = state.copy(status = "实时推送", error = null)
        poll?.let(main::removeCallbacks); poll = null
        if (state.streamedCandle && state.streamedSymbols.size >= state.coins.size) { failures = 0; socketFailures = 0 }
        emit()
    }
    private fun socketFailed(token: Long, attempt: Long, message: String) {
        handoff?.takeIf { it.second == attempt }?.let { it.first.cancel(); handoff = null; return }
        if (token != generation || attempt != socketSequence || owners.isEmpty()) return
        socketSequence++; socket?.cancel(); socket = null; socketFailures++
        if (socketFailures >= 2 && preferences.networkMode == "auto") { failed(token, message); return }
        state = state.copy(status = if (preferences.restFallback) "HTTP 兜底" else "重连中", error = "WS：$message", streamedSymbols = emptySet(), streamedCandle = false)
        emit()
        // Always backfill after an interrupted stream; HTTP samples never drive price alarms.
        snapshot(token)
        retry?.let(main::removeCallbacks)
        retry = Runnable { snapshot(token, initial = true) }.also { main.postDelayed(it, 15_000) }
    }
    private fun failed(token: Long, message: String) {
        if (token != generation || owners.isEmpty()) return
        cancelConnection(); failures = minOf(failures + 1, 5)
        if (preferences.networkMode == "auto") route = transport.alternate(route)
        // Automatic source: after two failures move to the next exchange in the chain.
        if (preferences.marketSource == "auto" && failures >= 2) {
            val chain = MarketSource.autoChain
            source = chain[(chain.indexOf(source) + 1) % chain.size]
            route = transport.initial(preferences.networkMode, source.id, preferences.lastGoodRoute)
            failures = 1
            state = state.copy(quotes = emptyMap(), candles = emptyList(), chartEventTime = 0)
        }
        state = state.copy(status = "重连中", error = message, streamedSymbols = emptySet(), streamedCandle = false)
        Log.w("PnaleMarket", "Reconnect via ${route.id}: $message"); emit()
        retry = Runnable { restart(resetBackoff = false) }.also { main.postDelayed(it, minOf(30_000L, 1000L shl failures)) }
    }
    private fun cancelConnection() {
        generation++; socketSequence++
        retry?.let(main::removeCallbacks); retry = null; poll?.let(main::removeCallbacks); poll = null
        socket?.cancel(); socket = null; snapshotLoading = false; connectAfterSnapshot = false
        handoff?.first?.cancel(); handoff = null
        requests.forEach { it.cancel() }; requests.clear()
    }
    private fun emit() { listeners.toList().forEach { it(state) } }
}
