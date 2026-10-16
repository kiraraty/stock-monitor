package app.pnale.ticker

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** Server-side alarm settings. A separate file keeps the token out of exported config backups. */
class ServerPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("server_alarm", Context.MODE_PRIVATE)
    var enabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        set(value) { prefs.edit().putBoolean("enabled", value).apply() }
    var token: String
        get() = prefs.getString("token", "")!!
        set(value) { prefs.edit().putString("token", value.trim()).apply() }
    /** Newest server event already handled; events at or before it are never rung again. */
    var cursor: Long
        get() = prefs.getLong("cursor", 0)
        set(value) { prefs.edit().putLong("cursor", value).apply() }
    var lastSync: Long
        get() = prefs.getLong("lastSync", 0)
        set(value) { prefs.edit().putLong("lastSync", value).apply() }
    var syncError: String
        get() = prefs.getString("syncError", "")!!
        set(value) { prefs.edit().putString("syncError", value).apply() }
}

data class ServerAlarmEvent(val id: String, val ruleId: String, val title: String, val detail: String, val observed: Long)

/**
 * The phone edits rules; the server stores and evaluates them on its trade stream.
 * Rules are pushed whenever they change; the running tracking service polls for fired events.
 */
class ServerAlarms(context: Context) {
    companion object {
        private const val BASE = "https://43.156.153.114/panel/v1"
        private val JSON = "application/json".toMediaType()
        /** Older events are shown as missed instead of ringing late. */
        const val RING_WINDOW_MS = 120_000L
        fun parseEvents(body: String): List<ServerAlarmEvent> {
            val list = JSONObject(body).getJSONArray("events")
            return (0 until list.length()).map { list.getJSONObject(it) }.filter { it.optString("kind") == "user_rule" }.map {
                val details = it.getJSONObject("details")
                ServerAlarmEvent(it.getString("id"), details.getString("rule_id"), it.optString("title"), details.optString("detail"), it.getLong("observed_at_ms"))
            }.sortedBy { it.observed }
        }
    }
    val prefs = ServerPreferences(context)
    private val alarmPrefs = context.getSharedPreferences("price_alarm", Context.MODE_PRIVATE)
    private val client by lazy { MarketTransport(context).relay!!.client }
    private val main = Handler(Looper.getMainLooper())
    private val pushTask = Runnable { push() }
    private var polling: Call? = null
    @Volatile var status = ""; private set
    private val rulesChanged = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "rules" && prefs.enabled) { main.removeCallbacks(pushTask); main.postDelayed(pushTask, 1_500) }
    }
    init { alarmPrefs.registerOnSharedPreferenceChangeListener(rulesChanged) }

    private fun request(path: String) = Request.Builder().url(BASE + path).header("Authorization", "Bearer ${prefs.token}")

    /** Sends the whole rule list; the server replaces its copy atomically. */
    fun push(done: ((String?) -> Unit)? = null) {
        main.removeCallbacks(pushTask)
        if (prefs.token.isBlank()) { finishPush("请先填写服务器令牌", done); return }
        val rules = JSONArray(alarmPrefs.getString("rules", "[]"))
        val body = JSONObject().put("rules", rules).toString().toRequestBody(JSON)
        client.newCall(request("/user-rules").put(body).build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { main.post { finishPush("连接服务器失败：${e.message}", done) } }
            override fun onResponse(call: Call, response: Response) {
                val text = response.use { it.body?.string().orEmpty() }
                val error = when {
                    response.isSuccessful -> null
                    response.code == 401 -> "令牌不正确"
                    response.code == 404 -> "服务器未开启手机同步"
                    response.code == 422 -> runCatching { "币安没有：" + JSONObject(text).getJSONArray("missing").join("、").replace("\"", "") }.getOrDefault("有币种服务器不支持")
                    else -> "同步失败 HTTP ${response.code}"
                }
                main.post { if (error == null) prefs.lastSync = System.currentTimeMillis(); finishPush(error, done) }
            }
        })
    }
    private fun finishPush(error: String?, done: ((String?) -> Unit)?) { prefs.syncError = error.orEmpty(); done?.invoke(error) }

    /** One poll for fired rules newer than the cursor. Results arrive on the main thread. */
    fun poll(onEvents: (List<ServerAlarmEvent>) -> Unit) {
        if (!prefs.enabled || prefs.token.isBlank() || polling != null) return
        val call = client.newCall(request("/events?kind=user_rule&limit=50&after_ms=${prefs.cursor}").get().build())
        polling = call
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { main.post { polling = null; status = "服务器暂不可达：${e.message}" } }
            override fun onResponse(call: Call, response: Response) {
                val events = response.use { r -> if (r.isSuccessful) runCatching { parseEvents(r.body!!.string()) }.getOrNull() else null }
                main.post {
                    polling = null
                    if (events == null) { status = "拉取服务器警报失败 HTTP ${response.code}"; return@post }
                    status = "服务器监测中 · ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())} 已同步"
                    val fresh = events.filter { it.observed > prefs.cursor }
                    if (fresh.isNotEmpty()) { prefs.cursor = fresh.maxOf { it.observed }; onEvents(fresh) }
                }
            }
        })
    }

    /** Server copy of the rules plus each rule's live status (window progress, cooldown). */
    @Volatile var ruleStatus: Map<String, String> = emptyMap(); private set
    fun fetch(done: (JSONArray?, String?) -> Unit) {
        if (prefs.token.isBlank()) { done(null, "请先填写服务器令牌"); return }
        client.newCall(request("/user-rules").get().build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { main.post { done(null, "连接服务器失败：${e.message}") } }
            override fun onResponse(call: Call, response: Response) {
                val body = response.use { if (it.isSuccessful) it.body?.string() else null }
                val parsed = body?.let { runCatching { JSONObject(it) }.getOrNull() }
                main.post {
                    if (parsed == null) { done(null, if (response.code == 401) "令牌不正确" else "读取失败 HTTP ${response.code}"); return@post }
                    val status = parsed.optJSONObject("status")
                    ruleStatus = status?.keys()?.asSequence()?.associateWith { status.getString(it) }.orEmpty()
                    done(parsed.getJSONArray("rules"), null)
                }
            }
        })
    }
    /** Replaces the phone's rules with the server copy (e.g. on a new phone). */
    fun restore(rules: JSONArray, into: AlarmPreferences) {
        into.rules = (0 until rules.length()).mapNotNull { runCatching { AlarmPreset.parse(rules.getJSONObject(it)) }.getOrNull() }
    }

    /** Turning server mode on starts from now, so old server events never ring. */
    fun enable(on: Boolean, done: (String?) -> Unit) {
        prefs.enabled = on
        if (!on) { done(null); return }
        prefs.cursor = System.currentTimeMillis()
        push(done)
    }
}
