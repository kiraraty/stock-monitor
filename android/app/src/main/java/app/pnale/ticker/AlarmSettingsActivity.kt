package app.pnale.ticker

import android.content.Intent
import android.media.AudioManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.*

/** Alarm home: status, the rule list at a glance, then global settings. Rules are edited in [AlarmRuleActivity]. */
class AlarmSettingsActivity : AlarmPage() {
    private lateinit var headline: TextView
    private lateinit var detail: TextView
    private lateinit var ruleBox: LinearLayout
    private lateinit var serverStatus: TextView
    private lateinit var deliveryStatus: TextView
    private lateinit var toneLabel: TextView
    private var shownRules = emptyList<AlarmPreset>()
    private val ruleStatus = mutableMapOf<String, TextView>()
    private var lastServerFetch = 0L
    private val update = object : Runnable { override fun run() { refresh(); headline.postDelayed(this, 1_000) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val page = SettingsPage(this, "价格警报", "规则一览 · 点开规则编辑")
        val content = page.content

        val status = content.section("监测状态")
        headline = text("", 16f, Colors.green, true); status.addView(headline)
        detail = text("", 12f, Colors.muted); status.addView(detail, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        status.actions("试听 10 秒" to { testTone(true, true) }, "停止响铃" to { stopRinging() })

        val rules = content.section("我的规则", "开关直接启用或停用 · 最多 20 条")
        ruleBox = column(); rules.addView(ruleBox)
        rules.action("＋ 新建规则", true) {
            if (prefs.rules.size >= 20) Toast.makeText(this, "最多保存 20 条规则", Toast.LENGTH_SHORT).show()
            else startActivity(Intent(this, AlarmRuleActivity::class.java))
        }

        val server = content.section("服务器监测", "规则同步到新加坡服务器，由服务器盯盘", true, !app.serverAlarms.prefs.enabled && app.serverAlarms.prefs.token.isBlank())
        serverStatus = text("", 12f, Colors.muted); server.addView(serverStatus)
        val token = server.input("访问令牌", "服务器访问令牌", plain = true).apply {
            setText(app.serverAlarms.prefs.token); inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        fun saveToken() { app.serverAlarms.prefs.token = token.text.toString() }
        lateinit var serverToggle: Switch
        serverToggle = server.toggle("由服务器监测我的规则", app.serverAlarms.prefs.enabled, current = { app.serverAlarms.prefs.enabled }) { on ->
            saveToken()
            app.serverAlarms.enable(on) { error ->
                if (error != null) { app.serverAlarms.prefs.enabled = false; serverToggle.isChecked = false }
                else if (on && prefs.rules.isEmpty()) offerRestore()
                refresh()
            }
        }
        server.actions("上传到服务器" to { saveToken(); app.serverAlarms.push { refresh() } }, "从服务器恢复" to { saveToken(); offerRestore(always = true) })
        server.note("服务器用币安逐笔成交判断，手机断网或休眠时也不中断采集；手机需保持盯盘开启，约每 5 秒拉取结果并在本机响铃。超过 2 分钟才收到的警报只记录、不补响。规则一改会自动上传。")

        val delivery = content.section("提醒时段", "白天轻提醒，夜间及时唤醒", true, false)
        deliveryStatus = text("", 13f, Colors.green, true); delivery.addView(deliveryStatus)
        delivery.choices("提醒模式", listOf("auto" to "日夜自动", "vibrate" to "仅振动", "ring" to "铃声＋振动"), prefs.deliveryMode, 3, current = { prefs.deliveryMode }) { prefs.deliveryMode = it; refresh() }
        val times = row()
        lateinit var from: Button
        lateinit var until: Button
        fun refreshTimes() { from.text = "夜间开始  ${AlarmSchedule.label(prefs.nightStart)}"; until.text = "夜间结束  ${AlarmSchedule.label(prefs.nightEnd)}" }
        fun chooseTime(start: Boolean) {
            val value = if (start) prefs.nightStart else prefs.nightEnd
            android.app.TimePickerDialog(this, { _, hour, minute ->
                val next = hour * 60 + minute
                val begin = if (start) next else prefs.nightStart; val end = if (start) prefs.nightEnd else next
                if (begin == end) Toast.makeText(this, "开始和结束时间不能相同", Toast.LENGTH_SHORT).show()
                else { prefs.setNight(begin, end); refreshTimes(); refresh() }
            }, value / 60, value % 60, true).show()
        }
        from = button("") { chooseTime(true) }.apply { contentDescription = "设置夜间开始时间" }
        until = button("") { chooseTime(false) }.apply { contentDescription = "设置夜间结束时间" }
        times.addView(from, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) }); times.addView(until, LinearLayout.LayoutParams(0, -2, 1f))
        delivery.addView(times, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) }); refreshTimes()
        delivery.note("自动：白天只振动，夜间铃声＋振动。规则里关掉的铃声或振动始终关闭。")

        val audioSettings = content.section("铃声与系统权限", "声音、音量和全屏提醒", true, false)
        toneLabel = text("", 12f, Colors.muted); audioSettings.addView(toneLabel)
        audioSettings.action("选择警报铃声") {
            val existing = prefs.ringtone.takeIf { it.isNotBlank() }?.let(Uri::parse) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            runCatching { startActivityForResult(Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, existing), 42) }
                .onFailure { Toast.makeText(this, "系统铃声选择器不可用", Toast.LENGTH_SHORT).show() }
        }
        val audio = getSystemService(AudioManager::class.java)
        audioSettings.slider("系统闹钟音量", 0, audio.getStreamMaxVolume(AudioManager.STREAM_ALARM), audio.getStreamVolume(AudioManager.STREAM_ALARM), "") {
            runCatching { audio.setStreamVolume(AudioManager.STREAM_ALARM, it, 0) }.onFailure { Toast.makeText(this, "请到系统声音设置调整音量", Toast.LENGTH_SHORT).show() }
        }
        audioSettings.note("此音量也会影响手机上的其他闹钟。")
        audioSettings.actions("全屏提醒权限" to { runCatching { startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName"))) }; Unit }, "声音 / 勿扰" to { runCatching { startActivity(Intent(Settings.ACTION_SOUND_SETTINGS)) }; Unit })

        val backup = content.section("备份与帮助", "规则随配置自动备份到本机", true, false)
        backup.note("规则、提醒时段和铃声会随全部配置自动备份在本机，可在「配置与备份」恢复或导出 JSON 换机。开启服务器监测后，服务器上也有一份，可用「从服务器恢复」取回。")
        backup.action("配置与备份") { startActivity(Intent(this, ConfigActivity::class.java)) }
        backup.note("短时涨跌需先采满观察窗口，断线后重新采集；穿越价格需先观察到阈值另一侧。本地模式用手机收到的行情，休眠或断网可能漏报。")
        backup.action("停用全部规则") { prefs.rules = prefs.rules.map { it.copy(enabled = false) }; stopRinging(); refresh() }
        refresh()
    }

    private fun offerRestore(always: Boolean = false) {
        app.serverAlarms.fetch { rules, error ->
            if (isDestroyed) return@fetch
            if (error != null) { Toast.makeText(this, error, Toast.LENGTH_LONG).show(); return@fetch }
            if (rules == null || rules.length() == 0) { if (always) Toast.makeText(this, "服务器上还没有规则", Toast.LENGTH_SHORT).show(); return@fetch }
            android.app.AlertDialog.Builder(this).setTitle("用服务器上的规则替换本机？")
                .setMessage("服务器上有 ${rules.length()} 条，本机有 ${prefs.rules.size} 条。替换后本机规则会被覆盖（本机自动备份仍可在「配置与备份」恢复）。")
                .setNegativeButton("取消", null).setPositiveButton("替换") { _, _ -> app.serverAlarms.restore(rules, prefs); refresh() }.show()
        }
    }

    private fun renderRules(rules: List<AlarmPreset>) {
        shownRules = rules; ruleBox.removeAllViews(); ruleStatus.clear()
        if (rules.isEmpty()) { ruleBox.addView(text("还没有规则。新建一条，例如「BTC 5 分钟内跌 2%」。", 13f, Colors.muted), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) }); return }
        rules.forEachIndexed { i, rule ->
            val card = row().apply {
                setPadding(0, dp(12), 0, dp(12)); isClickable = true; isFocusable = true; contentDescription = "编辑 ${rule.name}"
                setOnClickListener { startActivity(Intent(this@AlarmSettingsActivity, AlarmRuleActivity::class.java).putExtra("id", rule.id)) }
            }
            val info = column()
            info.addView(row().apply {
                addView(text(rule.rule.coin, 12f, Colors.background, true).apply {
                    background = rounded(if (rule.enabled) Colors.green else Colors.muted, 6); setPadding(dp(7), dp(2), dp(7), dp(2))
                })
                addView(text(rule.name, 15f, bold = true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) })
            })
            info.addView(text(AlarmText.condition(rule.rule), 14f), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
            val line = text("", 11f, Colors.muted); ruleStatus[rule.id] = line
            info.addView(line, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
            card.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
            card.addView(Switch(this).apply {
                isChecked = rule.enabled; contentDescription = "启用 ${rule.name}"
                setOnCheckedChangeListener { _, on -> toggle(rule.id, on) }
            }, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_VERTICAL; marginStart = dp(8) })
            ruleBox.addView(card)
            if (i < rules.lastIndex) ruleBox.addView(View(this).apply { setBackgroundColor(Colors.line) }, LinearLayout.LayoutParams(-1, dp(1)))
        }
    }

    private fun toggle(id: String, on: Boolean) {
        fun apply() { prefs.rules = prefs.rules.map { if (it.id == id) it.copy(enabled = on, lastFired = if (on) 0 else it.lastFired) else it } }
        if (on) {
            val rule = prefs.rules.firstOrNull { it.id == id } ?: return
            if (rule.rule.coin !in app.preferences.coins) { Toast.makeText(this, "请先把 ${rule.rule.coin} 加入自选", Toast.LENGTH_LONG).show(); refresh(force = true); return }
            arm { apply() }
        } else apply()
        window.decorView.postDelayed({ refresh() }, 200)
    }

    private fun statusOf(rule: AlarmPreset): String {
        val server = app.serverAlarms.prefs.enabled
        return when {
            !rule.enabled && rule.lastFired > 0 -> "已触发 ${AlarmText.time(rule.lastFired)} · 已停用"
            !rule.enabled -> "未启用"
            server -> app.serverAlarms.ruleStatus[rule.id]?.let { "服务器：$it" } ?: "服务器监测中"
            !OverlayService.running -> "等待盯盘启动"
            else -> OverlayService.alarmRuleStatuses[rule.id] ?: "监测中"
        }
    }

    private fun refresh(force: Boolean = false) {
        if (!::headline.isInitialized) return
        val rules = prefs.rules
        if (force || rules != shownRules) renderRules(rules)
        rules.forEach { rule -> ruleStatus[rule.id]?.text = statusOf(rule) }
        val sp = app.serverAlarms.prefs
        val enabled = rules.count { it.enabled }
        headline.text = when {
            enabled == 0 -> "没有启用的规则"
            sp.enabled -> "服务器监测 $enabled 条规则"
            OverlayService.running -> "手机监测 $enabled 条规则"
            else -> "$enabled 条已启用 · 盯盘未启动"
        }
        headline.setTextColor(if (enabled > 0 && (sp.enabled || OverlayService.running)) Colors.green else Colors.orange)
        val currentDelivery = prefs.delivery()
        detail.text = "现在：${if (currentDelivery.sound) "铃声＋振动" else "仅振动"}" + (if (prefs.deliveryMode == "auto") " · 日夜自动" else "") +
            "\n上次：${prefs.lastEvent}" + if (getSystemService(android.app.NotificationManager::class.java).canUseFullScreenIntent()) "" else "\n全屏提醒未允许，锁屏时可能只显示通知"
        deliveryStatus.text = "当前：${if (currentDelivery.sound) "铃声＋振动" else "仅振动"}" + if (prefs.deliveryMode == "auto") "  ·  日夜自动" else "  ·  手动模式"
        toneLabel.text = if (prefs.ringtone.isBlank()) "所有规则使用系统默认闹钟铃声" else "所有规则使用你选择的铃声"
        serverStatus.text = when {
            sp.syncError.isNotEmpty() -> "同步失败：${sp.syncError}"
            !sp.enabled -> "未开启 · 当前由手机本地监测"
            sp.lastSync > 0 -> "已上传 ${AlarmText.time(sp.lastSync)}" + app.serverAlarms.status.takeIf { it.isNotEmpty() }?.let { "\n$it" }.orEmpty()
            else -> "等待首次上传"
        }
        serverStatus.setTextColor(if (sp.syncError.isNotEmpty()) Colors.orange else if (sp.enabled) Colors.green else Colors.muted)
        // Server-side window progress for the cards, at most every 5 seconds while this page is open.
        if (sp.enabled && android.os.SystemClock.elapsedRealtime() - lastServerFetch > 5_000) {
            lastServerFetch = android.os.SystemClock.elapsedRealtime(); app.serverAlarms.fetch { _, _ -> }
        }
    }

    @Deprecated("Framework callback")
    override fun onActivityResult(code: Int, result: Int, data: Intent?) {
        super.onActivityResult(code, result, data)
        if (code == 42 && result == RESULT_OK) { data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)?.let { prefs.ringtone = it.toString() }; refresh() }
    }
    override fun onStart() { super.onStart(); headline.post(update) }
    override fun onStop() { headline.removeCallbacks(update); super.onStop() }
}
