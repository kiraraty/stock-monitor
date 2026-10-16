package app.pnale.ticker

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

class StyleActivity : PanelActivity() {
    private val app get() = application as PnaleApp
    private val prefs get() = app.preferences
    private lateinit var preview: MarketBoard
    private lateinit var topPreview: TextView
    private lateinit var colorLabel: TextView
    private val chipPreviews = mutableMapOf<String, Pair<TextView, TextView>>()
    private val fonts = listOf("system" to "标准", "light" to "纤细", "bold" to "粗体", "mono" to "等宽", "serif" to "衬线")
    private val listener: (MarketState) -> Unit = { preview.render(it); renderPreviews(it) }
    private val refreshDisplay = Runnable {
        if (OverlayService.running) {
            startService(Intent(this, OverlayService::class.java).setAction(OverlayService.RESIZE))
            startService(Intent(this, OverlayService::class.java).setAction(OverlayService.REFRESH))
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); buildUi() }
    private fun changed() {
        preview.invalidate(); renderPreviews(app.market.state); Typography.apply(window.decorView)
        window.decorView.removeCallbacks(refreshDisplay); window.decorView.postDelayed(refreshDisplay, 350)
    }
    private fun renderPreviews(state: MarketState) {
        if (::topPreview.isInitialized) {
            topPreview.text = TopPriceStyle.label(prefs, state, sample = "1234.56")
            // Preview shows appearance even before live data is ready; live overlay retains stale indication.
            TopPriceStyle.apply(topPreview, prefs)
            colorLabel.text = if (prefs.topDirection) "当前：跟随 24h 涨跌 · 点选颜色可切回固定色" else "固定颜色 ${TopPriceStyle.hex(prefs.topColor)} · 已自动保存"
        }
        chipPreviews.forEach { (layout, labels) -> labels.first.text = DisplayStyle.chipLeft(state, layout); labels.second.text = DisplayStyle.chipRight(state, layout, prefs.chipDecimals) }
    }
    private fun buildUi() {
        chipPreviews.clear()
        val page = SettingsPage(this, "外观与字体", "选好即保存 · 可随时恢复默认")
        val content = page.content
        val typography = content.section("界面字体", "适用于首页、设置和按钮")
        typography.addView(text("Panel  1,234.56\n清楚地看见每一次变化", 20f, bold = true).apply { setPadding(dp(4), dp(8), dp(4), dp(12)); contentDescription = "界面字体预览" })
        typography.choices("界面字体", fonts.filter { it.first != "bold" }, prefs.uiFont, current = { prefs.uiFont }) { prefs.uiFont = it; changed() }
        typography.choices("界面字号", listOf("90" to "紧凑", "100" to "标准", "110" to "大", "120" to "更大"), prefs.uiTextScale.toString(), 4, current = { prefs.uiTextScale.toString() }) { prefs.uiTextScale = it.toInt(); changed() }

        val top = content.section("顶部 / 状态栏文字", "独立设置，不受界面字体影响")
        val sampleBar = row().apply { background = rounded(Color.rgb(6, 9, 13), 12); setPadding(dp(12), dp(10), dp(12), dp(10)) }
        sampleBar.addView(text("9:41", 12f, Colors.muted), LinearLayout.LayoutParams(0, dp(44), 1f))
        topPreview = text("1234.56", 15f).apply { gravity = Gravity.CENTER; contentDescription = "顶部价格样式预览"; setTag(R.id.ui_custom_font, true) }
        sampleBar.addView(topPreview, LinearLayout.LayoutParams(0, dp(44), 4f))
        sampleBar.addView(text("▰", 15f, Colors.muted).apply { gravity = Gravity.END }, LinearLayout.LayoutParams(0, dp(44), 1f))
        top.addView(sampleBar)
        top.choices("顶部文字字体", fonts, prefs.topFont, current = { prefs.topFont }) { prefs.topFont = it; changed() }
        top.slider("顶部文字字号", 10, 26, prefs.topTextSize, " sp", current = { prefs.topTextSize }) { prefs.topTextSize = it; changed() }
        colorLabel = text("", 12f, Colors.muted); top.addView(colorLabel)
        val colors = listOf(Color.WHITE to "白色", Colors.green to "薄荷", Colors.orange to "琥珀", Color.rgb(137,195,255) to "冰蓝", Color.BLACK to "黑色")
        top.choices("固定文字颜色", colors.map { TopPriceStyle.hex(it.first) to it.second }, TopPriceStyle.hex(prefs.topColor), 3,
            current = { if (prefs.topDirection) "direction" else TopPriceStyle.hex(prefs.topColor) }) { prefs.chooseTopColor(TopPriceStyle.parseColor(it)!!); changed() }
        top.action("自定义颜色") { pickColor() }
        top.toggle("颜色跟随 24h 涨跌", prefs.topDirection, current = { prefs.topDirection }) { prefs.topDirection = it; changed() }
        top.toggle("文字阴影，增强对比", prefs.topShadow, current = { prefs.topShadow }) { prefs.topShadow = it; changed() }
        top.note("颜色和字号用于顶部悬浮文字；数字通知图标共用字体，颜色和尺寸由系统控制。流体云卡片文字也由系统排版。")
        top.action("开启顶部价格 / 调整位置") { startActivity(Intent(this, MarketSettingsActivity::class.java).putExtra("section", "floating")) }

        val board = content.section("行情牌", "大字模式与充电屏保预览", true, false)
        preview = MarketBoard(this).apply { contentDescription = "行情牌样式预览" }
        board.addView(preview, LinearLayout.LayoutParams(-1, dp(205)))
        board.choices("面板样式", listOf("minimal" to "极简", "terminal" to "终端", "cards" to "卡片", "glass" to "玻璃"), prefs.boardStyle, current = { prefs.boardStyle }) { prefs.boardStyle = it; changed() }
        board.choices("排列", listOf("single" to "单币", "pair" to "上下", "row" to "并排", "grid" to "四宫格"), prefs.boardLayout, current = { prefs.boardLayout }) { prefs.boardLayout = it; changed() }
        board.choices("数字配色", listOf("white" to "白色", "mint" to "薄荷", "amber" to "琥珀", "blue" to "冰蓝"), prefs.boardPalette, current = { prefs.boardPalette }) { prefs.boardPalette = it; changed() }
        board.choices("行情数字字体", fonts, prefs.boardFont, current = { prefs.boardFont }) { prefs.boardFont = it; changed() }
        board.slider("数字大小", 75, 135, prefs.boardSize, current = { prefs.boardSize }) { prefs.boardSize = it; changed() }
        board.toggle("显示币种名称", prefs.boardNames, current = { prefs.boardNames }) { prefs.boardNames = it; changed() }
        board.toggle("显示 24h 涨跌", prefs.boardChanges, current = { prefs.boardChanges }) { prefs.boardChanges = it; changed() }
        board.toggle("价格随涨跌变色", prefs.boardDirection, current = { prefs.boardDirection }) { prefs.boardDirection = it; changed() }
        board.slider("屏保 / 大字模式亮度", 10, 100, prefs.boardBrightness, current = { prefs.boardBrightness }) { prefs.boardBrightness = it; changed() }
        board.action("打开大字行情牌") { startActivity(Intent(this, DeskActivity::class.java)) }

        val notification = content.section("通知与流体云", "选择显示内容与左右布局", true, false)
        notification.choices("通知内容", listOf("single" to "当前币", "pair" to "两个币", "list" to "币种列表"), prefs.notificationDetail, current = { prefs.notificationDetail }) { prefs.notificationDetail = it; changed() }
        notification.toggle("通知显示涨跌幅", prefs.notificationChanges, current = { prefs.notificationChanges }) { prefs.notificationChanges = it; changed() }
        notification.choices("胶囊左右布局", listOf("symbol_price" to "币种 + 价格", "symbol_change" to "币种 + 涨跌", "price_change" to "价格 + 涨跌", "price_only" to "图标 + 价格"), prefs.chipLayout, current = { prefs.chipLayout }) { prefs.chipLayout = it; changed() }
        listOf("symbol_price", "symbol_change", "price_change", "price_only").forEach { layout ->
            val chip = row().apply { setPadding(dp(12), dp(8), dp(12), dp(8)); background = rounded(Colors.background, 20); contentDescription = "$layout 内容预览" }
            val left = text("", 12f, Colors.green, true).apply { gravity = Gravity.CENTER; maxLines = 2 }
            val right = text("", 22f, bold = true).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL; maxLines = 1 }
            chip.addView(left, LinearLayout.LayoutParams(dp(88), dp(40))); chip.addView(right, LinearLayout.LayoutParams(0, dp(40), 1f)); chipPreviews[layout] = left to right
            notification.addView(chip, LinearLayout.LayoutParams(-1, dp(56)).apply { topMargin = dp(8) })
        }
        notification.choices("胶囊价格小数位", listOf("auto" to "完整", "0" to "整数", "1" to "1 位", "2" to "2 位"), prefs.chipDecimals, 4, current = { prefs.chipDecimals }) { prefs.chipDecimals = it; changed() }
        notification.note("小价格不会取整成 0。胶囊能否展示，以及宽度、背景和圆角，以手机系统为准。")
        content.action("恢复默认外观") {
            android.app.AlertDialog.Builder(this).setTitle("恢复默认外观？").setMessage("将重置字体、顶部文字和行情牌样式。")
                .setNegativeButton("取消", null).setPositiveButton("恢复默认") { _, _ -> preview.stop(); prefs.resetStyles(); buildUi(); preview.start(); changed() }.show()
        }
        renderPreviews(app.market.state)
    }
    private fun pickColor() {
        val box = column().apply { setPadding(dp(22), dp(12), dp(22), dp(12)) }
        val color = box.input("颜色代码", "自定义顶部文字颜色", plain = true).apply { setText(TopPriceStyle.hex(prefs.topColor)); setSelection(text.length) }
        box.note("输入六位颜色，例如 #75F5D0。")
        val dialog = android.app.AlertDialog.Builder(this).setTitle("自定义文字颜色").setView(box).setNegativeButton("取消", null).setPositiveButton("应用", null).create()
        dialog.setOnShowListener { dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val parsed = TopPriceStyle.parseColor(color.text.toString())
            if (parsed == null) color.error = "请输入 #RRGGBB 格式的六位颜色" else { prefs.chooseTopColor(parsed); changed(); dialog.dismiss() }
        } }; dialog.show()
    }
    override fun onStart() { super.onStart(); preview.start(); app.market.listen(listener); app.market.acquire(this) }
    override fun onStop() { window.decorView.removeCallbacks(refreshDisplay); refreshDisplay.run(); preview.stop(); app.market.unlisten(listener); app.market.release(this); super.onStop() }
}
