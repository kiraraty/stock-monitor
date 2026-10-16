package app.pnale.ticker

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.WindowInsets
import android.widget.LinearLayout

class AlarmActivity : PanelActivity() {
    private lateinit var status: android.widget.TextView
    private val tick = object : Runnable {
        override fun run() {
            status.text = AlarmController.message + if (AlarmController.ringing) "\n\n警报进行中" else "\n\n警报已结束"
            status.postDelayed(this, 500)
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true); setTurnScreenOn(true)
        val content = column().apply {
            gravity = Gravity.CENTER; setPadding(dp(24), dp(24), dp(24), dp(24)); setBackgroundColor(Colors.background)
        }
        content.addView(text("行情警报", 30f, Colors.orange, true))
        content.space(24)
        status = text(AlarmController.message, 20f).apply { gravity = Gravity.CENTER }
        content.addView(status)
        content.space(36)
        content.addView(button("停止响铃", true) { stopAndClose() }, LinearLayout.LayoutParams(-1, dp(76)))
        content.space(16)
        content.addView(text("单次规则触发后停用；重复规则按设定的冷却时间继续监测。", 12f, Colors.muted))
        setContentView(content)
        content.setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            content.setPadding(dp(24) + bars.left, dp(24) + bars.top, dp(24) + bars.right, dp(24) + bars.bottom); insets
        }
        onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) { stopAndClose() }
    }
    private fun stopAndClose() {
        if (OverlayService.running) startService(Intent(this, OverlayService::class.java).setAction(OverlayService.STOP_ALARM))
        finish()
    }
    override fun onStart() { super.onStart(); status.post(tick) }
    override fun onStop() { status.removeCallbacks(tick); super.onStop() }
}
