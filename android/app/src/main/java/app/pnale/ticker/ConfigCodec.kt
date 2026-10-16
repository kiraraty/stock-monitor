package app.pnale.ticker

import org.json.JSONArray
import org.json.JSONObject

/** Versioned, allowlisted configuration. Parse the entire file before touching live preferences. */
object ConfigCodec {
    const val MAX_BYTES = 256 * 1024
    private val booleans = setOf("compact", "lockscreenQuotes", "liveUpdates", "topPrice", "topPositionSet", "topDirection", "topShadow",
        "boardNames", "boardChanges", "boardDirection", "notificationChanges", "trackingEnabled", "resumeOnBoot", "restFallback", "topSymbols")
    private val ranges = mapOf("x" to 0..20000, "y" to 0..20000, "floatX" to 0..20000, "floatY" to 0..20000, "overlayScalePercent" to 65..125, "overlayOpacityPercent" to 35..100,
        "boardBrightness" to 10..100, "uiTextScale" to 90..120, "topTextSize" to 10..26, "boardSize" to 75..135, "topPageSize" to 1..6, "topRotateSeconds" to 2..15)
    private val options = mapOf("interval" to listOf("1m", "5m", "15m", "1h"), "statusIconMode" to listOf("price", "multi", "off"),
        "boardStyle" to listOf("minimal", "terminal", "cards", "glass"), "boardLayout" to listOf("single", "pair", "row", "grid"),
        "boardPalette" to listOf("white", "mint", "amber", "blue"), "boardFont" to listOf("system", "light", "bold", "mono", "serif"),
        "uiFont" to listOf("system", "light", "mono", "serif"), "topFont" to listOf("system", "light", "bold", "mono", "serif"),
        "notificationDetail" to listOf("single", "pair", "list"), "chipLayout" to listOf("symbol_price", "symbol_change", "price_change", "price_only"),
        "chipDecimals" to listOf("auto", "0", "1", "2"), "trackingMode" to listOf("notification", "floating", "top"), "networkMode" to listOf("auto", "direct", "relay"), "marketSource" to listOf("auto", "binance", "gate", "htx"), "lastGoodSource" to listOf("binance", "gate", "htx"), "lastGoodRoute" to listOf("direct", "relay"))
    private fun int(value: Any): Int {
        require(value is Number && value.toDouble().isFinite() && value.toDouble() == value.toInt().toDouble()) { "配置整数格式错误" }
        return value.toInt()
    }
    fun encode(watchlist: Map<String, *>, alarm: Map<String, *>, now: Long = System.currentTimeMillis()): String =
        JSONObject().put("app", "app.pnale.ticker").put("version", 1).put("savedAt", now)
            .put("watchlist", JSONObject(watchlist)).put("alarm", JSONObject(alarm)).toString(2)

    fun decode(raw: String): Pair<Map<String, Any>, Map<String, Any>> {
        require(raw.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "配置文件过大" }
        val root = JSONObject(raw)
        require(root.getString("app") == "app.pnale.ticker" && int(root.get("version")) == 1) { "不是支持的哨灯配置文件" }
        fun parse(j: JSONObject, alarm: Boolean) = buildMap<String, Any> {
            j.keys().forEach { key ->
                val v = j.get(key)
                val value: Any = if (!alarm) when {
                    key in booleans -> v.also { require(it is Boolean) }
                    key in ranges -> int(v).also { require(it in ranges.getValue(key)) }
                    key in options -> v.also { require(it is String && it in options.getValue(key)) }
                    key == "topColor" -> int(v)
                    key == "coins" -> (v as String).also { require(WatchlistInput.parse(it).all { c -> c.matches(Regex("[A-Z0-9]{1,30}")) }) }
                    key == "barCoins" -> (v as String).also { require(it.length <= 2000 && it.split(",").all { c -> c.isEmpty() || c.matches(Regex("[A-Z0-9]{1,30}")) }) }
                    key == "selected" -> (v as String).also { require(it.matches(Regex("[A-Z0-9]{1,30}"))) }
                    key == "watchlistDraft" -> (v as String).also { require(it.length <= 2000) }
                    else -> error("配置包含未知字段：$key")
                } else when (key) {
                    "deliveryMode" -> (v as String).also { require(it in listOf("auto", "vibrate", "ring")) }
                    "nightStart", "nightEnd" -> int(v).also { require(it in 0..1439) }
                    "ringtone" -> (v as String).also { require(it.length < 2000 && (it.isEmpty() || it.startsWith("content://"))) }
                    "lastEvent" -> (v as String).also { require(it.length <= 10000) }
                    "editorDraft" -> (v as String).also { require(it.length <= 10000); if (it.isNotEmpty()) JSONObject(it) }
                    "rules" -> (v as String).also { text ->
                        val rules = JSONArray(text); require(rules.length() <= 20)
                        val ids = mutableSetOf<String>()
                        repeat(rules.length()) { i ->
                            val rule = AlarmPreset.parse(rules.getJSONObject(i))
                            require(ids.add(rule.id) && rule.id.length <= 100 && rule.rule.coin.matches(Regex("[A-Z0-9]{1,30}")))
                        }
                    }
                    else -> error("配置包含未知字段：$key")
                }
                put(key, value)
            }
        }
        val watchlist = parse(root.getJSONObject("watchlist"), false)
        val alarm = parse(root.getJSONObject("alarm"), true)
        require((alarm["nightStart"] ?: 1320) != (alarm["nightEnd"] ?: 480)) { "夜间开始和结束不能相同" }
        return watchlist to alarm
    }
}
