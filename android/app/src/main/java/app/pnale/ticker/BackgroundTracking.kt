package app.pnale.ticker

import android.Manifest
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

object BackgroundTracking {
    fun allowed(context: Context): Boolean {
        val nm = context.getSystemService(NotificationManager::class.java)
        return context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED &&
            nm.areNotificationsEnabled() && nm.getNotificationChannel("floating_quotes")?.importance != NotificationManager.IMPORTANCE_NONE
    }
    fun resume(context: Context) {
        if (Preferences(context).trackingEnabled && !OverlayService.running && allowed(context)) {
            runCatching { context.startForegroundService(Intent(context, OverlayService::class.java).setAction(OverlayService.RESTORE)) }
        }
    }
    fun stop(context: Context) {
        Preferences(context).trackingEnabled = false
        context.stopService(Intent(context, OverlayService::class.java))
    }
}

class TrackingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = Preferences(context)
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED || intent.action == Intent.ACTION_BOOT_COMPLETED && prefs.resumeOnBoot) {
            BackgroundTracking.resume(context)
        }
    }
}
