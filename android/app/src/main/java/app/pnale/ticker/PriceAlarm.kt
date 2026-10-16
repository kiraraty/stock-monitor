package app.pnale.ticker

import android.content.Context
import java.util.ArrayDeque

data class AlarmRule(val coin: String, val kind: String, val value: Double, val minutes: Int)
data class PriceAlarmEvent(val title: String, val detail: String)

data class AlarmPreset(
    val id: String = java.util.UUID.randomUUID().toString(), val name: String = "新警报",
    val rule: AlarmRule = AlarmRule("ETH", "drop", 2.0, 5), val enabled: Boolean = false,
    val duration: Int = 60, val sound: Boolean = true, val vibration: Boolean = true,
    val repeat: Boolean = false, val cooldownMinutes: Int = 30, val lastFired: Long = 0
) {
    fun afterTrigger(now: Long) = copy(enabled = repeat, lastFired = now)
    fun json() = org.json.JSONObject().put("id", id).put("name", name).put("coin", rule.coin).put("kind", rule.kind)
        .put("value", rule.value).put("minutes", rule.minutes).put("enabled", enabled).put("duration", duration)
        .put("sound", sound).put("vibration", vibration).put("repeat", repeat).put("cooldownMinutes", cooldownMinutes).put("lastFired", lastFired)
    companion object {
        val kinds = listOf("drop", "rise", "price", "above")
        fun parse(j: org.json.JSONObject): AlarmPreset {
            val rule = AlarmRule(j.getString("coin"), j.getString("kind"), j.getDouble("value"), j.getInt("minutes"))
            require(rule.kind in kinds && rule.value.isFinite() && rule.value > 0 && rule.minutes in 1..60)
            require(rule.kind !in listOf("drop", "rise") || rule.value < 100)
            val id = j.getString("id"); require(id.isNotBlank() && rule.coin.isNotBlank())
            return AlarmPreset(id, j.optString("name", "警报").take(40), rule, j.optBoolean("enabled", false),
                j.optInt("duration", 60).coerceIn(10, 120), j.optBoolean("sound", true), j.optBoolean("vibration", true),
                j.optBoolean("repeat", false), j.optInt("cooldownMinutes", 30).coerceIn(1, 1440), j.optLong("lastFired", 0))
        }
    }
}
class AlarmPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("price_alarm", Context.MODE_PRIVATE)
    var editorDraft: String
        get() = prefs.getString("editorDraft", "")!!
        set(value) { prefs.edit().putString("editorDraft", value).apply() }
    var deliveryMode: String
        get() = prefs.getString("deliveryMode", "auto")?.takeIf { it in listOf("auto", "vibrate", "ring") } ?: "auto"
        set(value) { require(value in listOf("auto", "vibrate", "ring")); prefs.edit().putString("deliveryMode", value).apply() }
    var nightStart: Int
        get() = prefs.getInt("nightStart", 22 * 60).coerceIn(0, 1439)
        private set(value) { prefs.edit().putInt("nightStart", value).apply() }
    var nightEnd: Int
        get() = prefs.getInt("nightEnd", 8 * 60).coerceIn(0, 1439).let { if (it == nightStart) (nightStart + 600) % 1440 else it }
        private set(value) { prefs.edit().putInt("nightEnd", value).apply() }
    fun setNight(start: Int, end: Int) {
        require(start in 0..1439 && end in 0..1439 && start != end)
        prefs.edit().putInt("nightStart", start).putInt("nightEnd", end).apply()
    }
    var rules: List<AlarmPreset>
        get() = runCatching {
            val a = org.json.JSONArray(prefs.getString("rules", "[]"))
            (0 until a.length()).mapNotNull { runCatching { AlarmPreset.parse(a.getJSONObject(it)) }.getOrNull() }.distinctBy { it.id }.take(20)
        }.getOrDefault(emptyList())
        set(value) { require(value.size <= 20); check(prefs.edit().putString("rules", org.json.JSONArray().apply { value.forEach { put(it.json()) } }.toString()).commit()) }
    var lastEvent: String
        get() = prefs.getString("lastEvent", "尚未触发")!!
        set(value) { prefs.edit().putString("lastEvent", value).apply() }
    var ringtone: String
        get() = prefs.getString("ringtone", "")!!
        set(value) { prefs.edit().putString("ringtone", value).apply() }
}
class AlarmRuleBook {
    private val monitors = mutableMapOf<String, PriceAlarmMonitor>()
    var statuses: Map<String, String> = emptyMap(); private set
    fun reset() { monitors.clear(); statuses = emptyMap() }
    fun evaluate(rules: List<AlarmPreset>, state: MarketState, now: Long): List<Pair<AlarmPreset, PriceAlarmEvent>> {
        monitors.keys.retainAll(rules.filter { it.enabled }.map { it.id }.toSet())
        val status = mutableMapOf<String, String>()
        val hits = mutableListOf<Pair<AlarmPreset, PriceAlarmEvent>>()
        for (preset in rules.filter { it.enabled }) {
            if (preset.lastFired > 0 && now - preset.lastFired < preset.cooldownMinutes * 60_000L) {
                status[preset.id] = "冷却中 · ${preset.cooldownMinutes} 分钟"; monitors.remove(preset.id); continue
            }
            val m = monitors.getOrPut(preset.id) { PriceAlarmMonitor() }
            val event = m.update(preset.rule, state.quotes[preset.rule.coin + "USDT"], state.status == "实时推送", now)
            status[preset.id] = m.status
            if (event != null) { hits += preset to event; monitors.remove(preset.id) }
        }
        statuses = status
        return hits
    }
}

/** Full-window observed return, never 24h change or a gap-filled assumption. */
class PriceAlarmMonitor {
    private var lastRule: AlarmRule? = null
    private val samples = ArrayDeque<Quote>()
    private var armedCrossing = false
    var status = "警报未启用"
        private set
    fun reset(message: String = "警报未启用") { samples.clear(); armedCrossing = false; lastRule = null; status = message }
    fun update(rule: AlarmRule, quote: Quote?, live: Boolean, now: Long): PriceAlarmEvent? {
        if (rule.kind !in AlarmPreset.kinds || !rule.value.isFinite() || rule.value <= 0 || rule.minutes !in 1..60 || (rule.kind in listOf("drop", "rise") && rule.value >= 100)) { reset("规则无效"); return null }
        if (lastRule != rule) { reset(); lastRule = rule }
        if (!live || quote == null || !quote.price.isFinite() || quote.price <= 0 || quote.eventTime <= 0 || quote.stale(now) || quote.eventTime > now + 5_000 || quote.symbol != rule.coin + "USDT") {
            samples.clear(); armedCrossing = false; status = "行情未就绪，等待实时数据"; return null
        }
        if (samples.peekLast()?.let { quote.eventTime <= it.eventTime } == true) return null
        if (samples.peekLast()?.let { quote.eventTime - it.eventTime > 20_000 } == true) {
            samples.clear(); armedCrossing = false
        }
        if (rule.kind in listOf("price", "above")) {
            val up = rule.kind == "above"
            val fire = armedCrossing && (if (up) quote.price >= rule.value else quote.price <= rule.value)
            armedCrossing = if (up) quote.price < rule.value else quote.price > rule.value
            samples.clear(); samples.addLast(quote)
            status = if (armedCrossing) "等待${if (up) "突破" else "跌破"} ${Prices.format(rule.value)}" else "已在阈值触发侧，需返回另一侧后再穿越"
            return if (fire) PriceAlarmEvent("${rule.coin} ${if (up) "突破" else "跌破"}价格", "现价 ${Prices.format(quote.price)} USDT · 阈值 ${Prices.format(rule.value)}") else null
        }
        val target = quote.eventTime - rule.minutes * 60_000L
        samples.addLast(quote)
        while (samples.size > 1 && samples.elementAt(1).eventTime <= target) samples.removeFirst()
        val baseline = samples.peekFirst()
        if (baseline.eventTime > target || target - baseline.eventTime > 20_000) {
            val observed = (quote.eventTime - baseline.eventTime) / 1000
            status = "采集窗口：${observed}s / ${rule.minutes * 60}s"; return null
        }
        val up = rule.kind == "rise"
        val drop = (if (up) quote.price / baseline.price - 1 else 1 - quote.price / baseline.price) * 100
        val direction = if (up) "上涨" else "下跌"
        status = "${rule.minutes} 分钟${direction} ${String.format(java.util.Locale.US, "%.2f", drop)}% · 阈值 ${rule.value}%"
        return if (drop >= rule.value) PriceAlarmEvent("${rule.coin} 短时${if (up) "急涨" else "急跌"}", "${rule.minutes} 分钟${direction} ${String.format(java.util.Locale.US, "%.2f", drop)}% · 现价 ${Prices.format(quote.price)} USDT") else null
    }
}
