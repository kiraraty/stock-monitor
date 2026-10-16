package app.pnale.ticker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.VibratorManager

class AlarmController(private val service: OverlayService, private val onEnded: () -> Unit) {
    companion object {
        const val CHANNEL = "price_alarm_v1"
        const val ID = 19
        var ringing = false
            private set
        var message = "尚未触发"
            private set
    }
    private val manager = service.getSystemService(NotificationManager::class.java)
    private val audio = service.getSystemService(AudioManager::class.java)
    private val vibrator = service.getSystemService(VibratorManager::class.java).defaultVibrator
    private val handler = Handler(Looper.getMainLooper())
    private val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attrs)
        .setOnAudioFocusChangeListener { change -> if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) stop() }.build()
    private var player: MediaPlayer? = null
    private val wake = service.getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Panel:priceAlarm")
    private val timeout = Runnable { stop() }
    init {
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "行情响铃警报", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "用户设置的价格/急跌警报；铃声使用系统闹钟音量"
            setSound(null, null); enableVibration(false)
        })
    }
    fun start(event: PriceAlarmEvent, duration: Int, sound: Boolean = true, vibration: Boolean = true) {
        stop(false)
        if (!manager.areNotificationsEnabled() || manager.getNotificationChannel(CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE) return
        message = "${event.title}\n${event.detail}"
        val open = PendingIntent.getActivity(service, 40, Intent(service, AlarmActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(service, 41, Intent(service, OverlayService::class.java).setAction(OverlayService.STOP_ALARM), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(service, CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(event.title).setContentText(event.detail).setStyle(Notification.BigTextStyle().bigText(event.detail))
            .setCategory(Notification.CATEGORY_ALARM).setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(open).setDeleteIntent(stop).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "停止响铃", stop).build()).apply {
                if (sound && manager.canUseFullScreenIntent()) setFullScreenIntent(open, true)
            }.build()
        manager.notify(ID, notification)
        ringing = true
        wake.acquire((duration.coerceIn(10, 120) + 5) * 1000L)
        handler.postDelayed(timeout, duration.coerceIn(10, 120) * 1000L)
        if (sound && audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            runCatching {
                val custom = AlarmPreferences(service).ringtone
                val uri = custom.takeIf { it.isNotBlank() }?.let { android.net.Uri.parse(it) }
                    ?: RingtoneManager.getActualDefaultRingtoneUri(service, RingtoneManager.TYPE_ALARM)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                player = MediaPlayer()
                player!!.apply {
                    setAudioAttributes(attrs); setWakeMode(service, PowerManager.PARTIAL_WAKE_LOCK)
                    setDataSource(service, uri); isLooping = true
                    setOnPreparedListener { if (ringing && player === it) it.start() }
                    setOnErrorListener { _, _, _ -> message += "\n铃声播放失败，请检查系统闹钟铃声"; false }
                    prepareAsync()
                }
            }.onFailure { message += "\n铃声不可用：${it.javaClass.simpleName}" }
        } else if (sound) message += "\n未取得音频焦点，铃声可能未播放"
        if (vibration) runCatching { vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 500, 350, 500, 700), 0), attrs) }
    }
    fun stop(notifyEnd: Boolean = true) {
        val wasRinging = ringing
        ringing = false; handler.removeCallbacks(timeout)
        if (wake.isHeld) wake.release()
        player?.let { runCatching { it.stop() }; it.release() }; player = null
        audio.abandonAudioFocusRequest(focus); vibrator.cancel(); manager.cancel(ID)
        if (wasRinging && notifyEnd) onEnded()
    }
}
