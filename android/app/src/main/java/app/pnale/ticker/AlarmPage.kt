package app.pnale.ticker

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings

/** Shared by the alarm list and editor: permission checks before arming, test tone and stop. */
open class AlarmPage : PanelActivity() {
    protected val app get() = application as PnaleApp
    protected val prefs by lazy { AlarmPreferences(this) }
    private var pending: (() -> Unit)? = null

    /** Runs [then] once notifications are allowed, then starts the tracking service in alarm mode. */
    protected fun arm(then: () -> Unit) = whenAllowed { then(); startForegroundService(Intent(this, OverlayService::class.java).setAction(OverlayService.ARM_ALARM)) }
    protected fun testTone(sound: Boolean, vibration: Boolean) = whenAllowed {
        startForegroundService(Intent(this, OverlayService::class.java).setAction(OverlayService.TEST_ALARM).putExtra("sound", sound).putExtra("vibration", vibration))
    }
    protected fun stopRinging() { if (OverlayService.running) startService(Intent(this, OverlayService::class.java).setAction(OverlayService.STOP_ALARM)) }

    private fun whenAllowed(action: () -> Unit) {
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            pending = action; requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 41); return
        }
        val manager = getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled() || manager.getNotificationChannel(AlarmController.CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE ||
            manager.getNotificationChannel("floating_quotes")?.importance == NotificationManager.IMPORTANCE_NONE) {
            startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)); return
        }
        action()
    }
    @Deprecated("Framework callback")
    override fun onRequestPermissionsResult(code: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, permissions, results)
        val action = pending; pending = null
        if (code == 41 && action != null && results.firstOrNull() == PackageManager.PERMISSION_GRANTED) whenAllowed(action)
    }
}

/** Plain-language descriptions shared by the list cards and the editor preview. */
object AlarmText {
    fun number(value: Double): String = java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
    fun kindLabel(kind: String) = when (kind) { "drop" -> "急跌"; "rise" -> "急涨"; "price" -> "跌破"; else -> "突破" }
    fun condition(rule: AlarmRule) = when (rule.kind) {
        "drop" -> "${rule.minutes} 分钟内跌 ${number(rule.value)}%"
        "rise" -> "${rule.minutes} 分钟内涨 ${number(rule.value)}%"
        "price" -> "跌破 ${number(rule.value)} USDT"
        else -> "突破 ${number(rule.value)} USDT"
    }
    fun delivery(p: AlarmPreset) = listOfNotNull(if (p.sound) "铃声按时段" else null, if (p.vibration) "振动" else null).ifEmpty { listOf("仅通知") }.joinToString(" · ") +
        " · 最长 ${p.duration} 秒 · " + if (p.repeat) "冷却 ${p.cooldownMinutes} 分钟后可再提醒" else "只提醒一次"
    fun time(ms: Long) = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(ms))
}
