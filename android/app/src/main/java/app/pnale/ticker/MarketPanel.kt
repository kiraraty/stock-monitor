package app.pnale.ticker

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MarketPanel(context: Context, private val engine: MarketEngine) : LinearLayout(context) {
    private val coinsRow = context.row()
    private val pair = context.text("ETH / USDT 永续", 11f, Colors.muted)
    private val price = context.text("—", 29f, bold = true)
    private val change = context.text("—", 12f, bold = true)
    private val chart = CandleChart(context)
    private val metrics = context.text("", 10f, Colors.muted)
    private val status = context.text("连接中", 10f, Colors.muted)
    private val detail = context.text("", 9f, Colors.orange)
    private val sourceLabel = context.text("", 9f, Colors.muted)
    private val intervalViews = mutableMapOf<String, TextView>()
    private val coinViews = mutableMapOf<String, TextView>()
    private var displayedCoins: List<String> = emptyList()
    private val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    init {
        orientation = VERTICAL
        setPadding(context.dp(15), context.dp(8), context.dp(15), context.dp(13))
        addView(HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; addView(coinsRow) }, LayoutParams(-1, context.dp(42)))
        space(8); addView(pair); space(7)
        addView(context.row().apply {
            addView(price, LayoutParams(0, -2, 1f))
            addView(change)
        })
        price.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        price.maxLines = 1
        price.setAutoSizeTextTypeUniformWithConfiguration(15, 29, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
        space(10)
        addView(context.row().apply {
            addView(context.text("K 线", 10f, Colors.muted), LayoutParams(0, -2, 1f))
            listOf("1m", "5m", "15m", "1h").forEach { period ->
                val view = chip(period) { engine.interval(period) }
                intervalViews[period] = view
                addView(view, LayoutParams(context.dp(45), context.dp(36)))
            }
        })
        addView(chart, LayoutParams(-1, context.dp(155)))
        space(12); addView(metrics); space(12)
        addView(context.row().apply {
            addView(status, LayoutParams(0, -2, 1f))
            addView(sourceLabel)
            addView(chip("↻") { engine.restart() }.apply { contentDescription = "重新连接" }, LayoutParams(context.dp(36), context.dp(32)))
        })
        addView(detail)
    }

    private fun chip(label: String, action: () -> Unit) = context.text(label, 11f).apply {
        gravity = Gravity.CENTER; setPadding(context.dp(9), 0, context.dp(9), 0)
        maxLines = 1; minWidth = context.dp(48)
        background = context.rounded(Colors.card, 7)
        setOnClickListener { action() }
        isClickable = true; isFocusable = true
    }

    fun render(state: MarketState) {
        if (displayedCoins != state.coins) {
            displayedCoins = state.coins
            coinsRow.removeAllViews(); coinViews.clear()
            state.coins.forEach { coin ->
                val view = chip(coin) { engine.select(coin) }
                coinViews[coin] = view
                coinsRow.addView(view, LayoutParams(-2, context.dp(34)).apply { marginEnd = context.dp(6) })
            }
        }
        coinViews.forEach { (coin, view) -> view.setTextColor(if (coin == state.selected) Colors.green else Colors.muted) }
        intervalViews.forEach { (period, view) -> view.setTextColor(if (period == state.interval) Colors.green else Colors.muted) }
        val quote = state.quote
        pair.text = "${state.selected} / USDT 永续   ·   最新成交价"
        price.text = quote?.let { Prices.format(it.price) } ?: "—"
        price.setTextColor(if (quote?.stale() == false) Color.WHITE else Colors.muted)
        change.text = quote?.let { Prices.change(it.change) + " 24H" } ?: "—"
        change.setTextColor(quote?.let { Colors.direction(it.change) } ?: Colors.muted)
        metrics.text = quote?.let { "高 ${Prices.format(it.high)}   低 ${Prices.format(it.low)}   额 ${Prices.volume(it.volume)}" } ?: "24H 最高 —    最低 —    成交额 —"
        status.text = "● ${state.displayStatus()}" + (quote?.let { " ${time.format(Date(it.eventTime))}" } ?: "")
        status.setTextColor(if (state.status == "实时推送" && quote?.stale() == false) Colors.green else Colors.orange)
        detail.text = state.error ?: if (state.candles.isNotEmpty() && state.chartStale()) "K 线待更新" else ""
        detail.visibility = if (detail.text.isEmpty()) GONE else VISIBLE
        // The route says which exchange actually served this data (auto mode may fail over).
        sourceLabel.text = when (state.route) { "gate" -> "Gate"; "htx" -> "HTX"; "relay" -> "Binance · 中继"; else -> "Binance" } + " · 永续"
        chart.candles = state.candles
    }
}
