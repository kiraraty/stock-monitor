package app.pnale.ticker

import android.content.Context

class Preferences(context: Context, name: String = "watchlist") {
    private val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    var coins: List<String>
        get() = runCatching { WatchlistInput.parse(prefs.getString("coins", "ETH, BTC")!!) }.getOrDefault(WatchlistInput.defaults)
        set(value) { prefs.edit().putString("coins", value.joinToString(",")).apply() }
    var selected: String
        get() = prefs.getString("selected", "ETH")!!.let { if (it in coins) it else coins.first() }
        set(value) { prefs.edit().putString("selected", value).apply() }
    var interval: String
        get() = prefs.getString("interval", "1m")!!.let { if (it in listOf("1m", "5m", "15m", "1h")) it else "1m" }
        set(value) { prefs.edit().putString("interval", value).apply() }
    var trackingEnabled: Boolean
        get() = prefs.getBoolean("trackingEnabled", false)
        set(value) { check(prefs.edit().putBoolean("trackingEnabled", value).commit()) }
    var trackingMode: String
        get() = option("trackingMode", if (topPrice) "top" else "notification", listOf("notification", "floating", "top"))
        set(value) = put("trackingMode", value)
    var resumeOnBoot: Boolean
        get() = prefs.getBoolean("resumeOnBoot", false)
        set(value) { prefs.edit().putBoolean("resumeOnBoot", value).apply() }
    var networkMode: String
        get() = option("networkMode", "auto", listOf("auto", "direct", "relay"))
        set(value) = put("networkMode", value)
    var lastGoodRoute: String
        get() = option("lastGoodRoute", "direct", listOf("direct", "relay"))
        set(value) = put("lastGoodRoute", value)
    var lastGoodSource: String
        get() = option("lastGoodSource", "gate", listOf("binance", "gate", "htx"))
        set(value) = put("lastGoodSource", value)
    var marketSource: String
        get() = option("marketSource", "auto", listOf("auto", "binance", "gate", "htx"))
        set(value) = put("marketSource", value)
    var restFallback: Boolean
        get() = prefs.getBoolean("restFallback", true)
        set(value) { prefs.edit().putBoolean("restFallback", value).apply() }
    var watchlistDraft: String
        get() = prefs.getString("watchlistDraft", coins.joinToString(", "))!!
        set(value) = put("watchlistDraft", value)
    fun chooseTopColor(color: Int) {
        prefs.edit().putInt("topColor", color or 0xFF000000.toInt()).putBoolean("topDirection", false).apply()
    }
    var compact: Boolean
        get() = prefs.getBoolean("compact", false)
        set(value) { prefs.edit().putBoolean("compact", value).apply() }
    var x: Int
        get() = prefs.getInt("x", 16)
        set(value) { prefs.edit().putInt("x", value).apply() }
    var overlayScalePercent: Int
        get() = prefs.getInt("overlayScalePercent", 80).coerceIn(65, 125)
        set(value) { prefs.edit().putInt("overlayScalePercent", value.coerceIn(65, 125)).apply() }
    var statusIconMode: String
        get() = prefs.getString("statusIconMode", "price")!!.takeIf { it in listOf("price", "multi", "off") } ?: "price"
        set(value) { prefs.edit().putString("statusIconMode", value).apply() }
    var overlayOpacityPercent: Int
        get() = prefs.getInt("overlayOpacityPercent", 80).coerceIn(35, 100)
        set(value) { prefs.edit().putInt("overlayOpacityPercent", value.coerceIn(35, 100)).apply() }
    var lockscreenQuotes: Boolean
        get() = prefs.getBoolean("lockscreenQuotes", true)
        set(value) { prefs.edit().putBoolean("lockscreenQuotes", value).apply() }
    var liveUpdates: Boolean
        get() = prefs.getBoolean("liveUpdates", false)
        set(value) { prefs.edit().putBoolean("liveUpdates", value).apply() }
    var topPrice: Boolean
        get() = prefs.getBoolean("topPrice", false)
        set(value) { prefs.edit().putBoolean("topPrice", value).apply() }
    var topPositionSet: Boolean
        get() = prefs.getBoolean("topPositionSet", false)
        set(value) { prefs.edit().putBoolean("topPositionSet", value).apply() }
    var boardBrightness: Int
        get() = prefs.getInt("boardBrightness", 25).coerceIn(10, 100)
        set(value) { prefs.edit().putInt("boardBrightness", value.coerceIn(10, 100)).apply() }
    private fun option(key: String, fallback: String, values: List<String>) = prefs.getString(key, fallback)?.takeIf { it in values } ?: fallback
    private fun put(key: String, value: String) { prefs.edit().putString(key, value).apply() }
    var boardStyle: String
        get() = option("boardStyle", "glass", listOf("minimal", "terminal", "cards", "glass"))
        set(value) = put("boardStyle", value)
    var boardLayout: String
        get() = option("boardLayout", "pair", listOf("single", "pair", "row", "grid"))
        set(value) = put("boardLayout", value)
    var boardPalette: String
        get() = option("boardPalette", "white", listOf("white", "mint", "amber", "blue"))
        set(value) = put("boardPalette", value)
    var boardFont: String
        get() = option("boardFont", "light", listOf("system", "light", "bold", "mono", "serif"))
        set(value) = put("boardFont", value)
    var uiFont: String
        get() = option("uiFont", "system", listOf("system", "light", "mono", "serif"))
        set(value) = put("uiFont", value)
    var uiTextScale: Int
        get() = prefs.getInt("uiTextScale", 100).coerceIn(90, 120)
        set(value) { prefs.edit().putInt("uiTextScale", value.coerceIn(90, 120)).apply() }
    var topFont: String
        get() = option("topFont", "bold", listOf("system", "light", "bold", "mono", "serif"))
        set(value) = put("topFont", value)
    var topTextSize: Int
        get() = prefs.getInt("topTextSize", 15).coerceIn(10, 26)
        set(value) { prefs.edit().putInt("topTextSize", value.coerceIn(10, 26)).apply() }
    var topColor: Int
        get() = prefs.getInt("topColor", android.graphics.Color.WHITE)
        set(value) { prefs.edit().putInt("topColor", value or 0xFF000000.toInt()).apply() }
    var topDirection: Boolean
        get() = prefs.getBoolean("topDirection", false)
        set(value) { prefs.edit().putBoolean("topDirection", value).apply() }
    var topShadow: Boolean
        get() = prefs.getBoolean("topShadow", true)
        set(value) { prefs.edit().putBoolean("topShadow", value).apply() }
    var boardSize: Int
        get() = prefs.getInt("boardSize", 100).coerceIn(75, 135)
        set(value) { prefs.edit().putInt("boardSize", value.coerceIn(75, 135)).apply() }
    var boardNames: Boolean
        get() = prefs.getBoolean("boardNames", true)
        set(value) { prefs.edit().putBoolean("boardNames", value).apply() }
    var boardChanges: Boolean
        get() = prefs.getBoolean("boardChanges", true)
        set(value) { prefs.edit().putBoolean("boardChanges", value).apply() }
    var boardDirection: Boolean
        get() = prefs.getBoolean("boardDirection", false)
        set(value) { prefs.edit().putBoolean("boardDirection", value).apply() }
    var notificationDetail: String
        get() = option("notificationDetail", "list", listOf("single", "pair", "list"))
        set(value) = put("notificationDetail", value)
    var notificationChanges: Boolean
        get() = prefs.getBoolean("notificationChanges", true)
        set(value) { prefs.edit().putBoolean("notificationChanges", value).apply() }
    var chipLayout: String
        get() = option("chipLayout", "symbol_price", listOf("symbol_price", "symbol_change", "price_change", "price_only"))
        set(value) = put("chipLayout", value)
    var chipDecimals: String
        get() = option("chipDecimals", "auto", listOf("auto", "0", "1", "2"))
        set(value) = put("chipDecimals", value)
    fun resetStyles() {
        prefs.edit().apply {
            listOf("boardStyle", "boardLayout", "boardPalette", "boardFont", "boardSize", "boardNames", "boardChanges", "boardDirection", "notificationDetail", "notificationChanges", "chipDecimals", "chipLayout", "boardBrightness", "uiFont", "uiTextScale", "topFont", "topTextSize", "topColor", "topDirection", "topShadow", "topSymbols").forEach { remove(it) }
        }.apply()
    }
    var y: Int
        get() = prefs.getInt("y", 180)
        set(value) { prefs.edit().putInt("y", value).apply() }
    // The floating bar keeps its own position so dragging it never moves the top price; older installs inherit x/y once.
    var floatX: Int
        get() = prefs.getInt("floatX", if (topPositionSet) 16 else x)
        set(value) { prefs.edit().putInt("floatX", value).apply() }
    var floatY: Int
        get() = prefs.getInt("floatY", if (topPositionSet) 180 else y)
        set(value) { prefs.edit().putInt("floatY", value).apply() }
    /** Coins shown together in the top text and status bar icons, in watchlist order; empty follows the selected coin. */
    var barCoins: List<String>
        get() = prefs.getString("barCoins", "")!!.split(",").let { saved -> coins.filter { it in saved } }
        set(value) = put("barCoins", value.distinct().joinToString(","))
    var topPageSize: Int
        get() = prefs.getInt("topPageSize", 3).coerceIn(1, 6)
        set(value) { prefs.edit().putInt("topPageSize", value.coerceIn(1, 6)).apply() }
    var topRotateSeconds: Int
        get() = prefs.getInt("topRotateSeconds", 4).coerceIn(2, 15)
        set(value) { prefs.edit().putInt("topRotateSeconds", value.coerceIn(2, 15)).apply() }
    var topSymbols: Boolean
        get() = prefs.getBoolean("topSymbols", true)
        set(value) { prefs.edit().putBoolean("topSymbols", value).apply() }
}
