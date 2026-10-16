package app.pnale.ticker

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar

class DeskActivity : PanelActivity() {
    private val app get() = application as PnaleApp
    private lateinit var board: MarketBoard
    private val listener: (MarketState) -> Unit = { board.render(it) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply { screenBrightness = app.preferences.boardBrightness / 100f }
        val root = FrameLayout(this)
        board = MarketBoard(this).apply { contentDescription = "实时大字行情，点击显示控制" }
        root.addView(board, FrameLayout.LayoutParams(-1, -1))
        val controls = column().apply { setPadding(dp(16), dp(8), dp(16), dp(16)); setBackgroundColor(0xDD101319.toInt()) }
        controls.addView(text("点空白隐藏控制 · 返回键退出", 12f, Colors.muted))
        controls.addView(row().apply {
            addView(button("切换币种") {
                val state = app.market.state
                app.market.select(state.coins[(state.coins.indexOf(state.selected) + 1) % state.coins.size])
            }, LinearLayout.LayoutParams(0, dp(48), 1f))
            addView(button("退出大字模式") { finish() }, LinearLayout.LayoutParams(0, dp(48), 1f))
        })
        controls.addView(button("样式与外观") { startActivity(android.content.Intent(this, StyleActivity::class.java)) }, LinearLayout.LayoutParams(-1, dp(48)))
        controls.addView(text("屏幕亮度", 12f, Colors.muted))
        controls.addView(SeekBar(this).apply {
            max = 90; progress = app.preferences.boardBrightness - 10; contentDescription = "大字模式屏幕亮度"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, value: Int, user: Boolean) {
                    if (user) { app.preferences.boardBrightness = value + 10; window.attributes = window.attributes.apply { screenBrightness = (value + 10) / 100f } }
                }
                override fun onStartTrackingTouch(bar: SeekBar?) {}
                override fun onStopTrackingTouch(bar: SeekBar?) {}
            })
        }, LinearLayout.LayoutParams(-1, dp(40)))
        root.addView(controls, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        root.setOnApplyWindowInsetsListener { _, insets ->
            val safe = insets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            (controls.layoutParams as FrameLayout.LayoutParams).apply { bottomMargin = safe.bottom; leftMargin = safe.left; rightMargin = safe.right }
            insets
        }
        board.setOnClickListener { controls.visibility = if (controls.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
        setContentView(root)
        window.insetsController?.apply {
            hide(WindowInsets.Type.systemBars())
            systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

    }
    override fun onStart() { super.onStart(); window.attributes = window.attributes.apply { screenBrightness = app.preferences.boardBrightness / 100f }; board.start(); app.market.listen(listener); app.market.acquire(this) }
    override fun onStop() { board.stop(); app.market.unlisten(listener); app.market.release(this); super.onStop() }
}
