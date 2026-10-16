package app.pnale.ticker

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CandleChart(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val d get() = resources.displayMetrics.density
    private val time = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val gridDash = DashPathEffect(floatArrayOf(2 * d, 4 * d), 0f)
    private val priceDash = DashPathEffect(floatArrayOf(3 * d, 3 * d), 0f)
    private val date = Date()
    private fun timeLabel(timestamp: Long): String { date.time = timestamp; return time.format(date) }
    private var hovered: Int? = null
    var candles: List<Candle> = emptyList()
        set(value) { if (field != value) { field = value; invalidate() }; contentDescription = "${value.size} 根合约 K 线，最新价格 ${value.lastOrNull()?.let { Prices.format(it.close) } ?: "暂无"}" }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (candles.isEmpty()) {
            paint.color = Colors.muted; paint.textSize = 12 * d; paint.textAlign = Paint.Align.CENTER
            canvas.drawText("等待合约 K 线…", width / 2f, height / 2f, paint)
            return
        }
        val chartWidth = (width - 66 * d).coerceAtLeast(1f)
        val priceHeight = (height - 42 * d).coerceAtLeast(1f)
        val low = candles.minOf { it.low }; val high = candles.maxOf { it.high }
        val pad = maxOf((high - low) * .12, high * .00005)
        val bottom = low - pad; val top = high + pad
        fun y(price: Double) = ((top - price) / (top - bottom) * priceHeight).toFloat() + 4 * d
        val step = chartWidth / candles.size
        paint.typeface = Typeface.MONOSPACE; paint.strokeWidth = d * .6f
        for (i in 0..3) {
            val price = top - (top - bottom) * i / 3
            val py = y(price)
            paint.color = Colors.line; paint.pathEffect = gridDash
            canvas.drawLine(0f, py, chartWidth, py, paint)
            paint.pathEffect = null; paint.color = Colors.muted; paint.textSize = 8 * d; paint.textAlign = Paint.Align.RIGHT
            canvas.drawText(Prices.format(price), width.toFloat(), py + 3 * d, paint)
        }
        val maxVolume = candles.maxOf { it.volume }.coerceAtLeast(1.0)
        for ((i, candle) in candles.withIndex()) {
            val x = i * step + step / 2
            paint.color = Colors.direction(candle.close - candle.open); paint.strokeWidth = d
            canvas.drawLine(x, y(candle.high), x, y(candle.low), paint)
            val bodyTop = minOf(y(candle.open), y(candle.close))
            val bodyBottom = maxOf(y(candle.open), y(candle.close), bodyTop + d)
            canvas.drawRect(x - step * .32f, bodyTop, x + step * .32f, bodyBottom, paint)
            paint.alpha = 80
            val volumeHeight = (candle.volume / maxVolume * 16 * d).toFloat()
            canvas.drawRect(x - step * .32f, height - 22 * d - volumeHeight, x + step * .32f, height - 22 * d, paint)
            paint.alpha = 255
        }
        val last = candles.last()
        paint.color = Colors.direction(last.close - last.open)
        paint.pathEffect = priceDash
        canvas.drawLine(0f, y(last.close), chartWidth, y(last.close), paint)
        paint.pathEffect = null
        canvas.drawRoundRect(chartWidth + 2 * d, y(last.close) - 8 * d, width.toFloat(), y(last.close) + 8 * d, 3 * d, 3 * d, paint)
        paint.color = Colors.background; paint.textSize = 8 * d; paint.textAlign = Paint.Align.RIGHT
        canvas.drawText(Prices.format(last.close), width - 3 * d, y(last.close) + 3 * d, paint)
        paint.color = Colors.muted; paint.textSize = 9 * d; paint.textAlign = Paint.Align.LEFT
        val hover = hovered?.let { candles.getOrNull(it) }
        if (hover != null) {
            val x = hovered!! * step + step / 2
            paint.color = Colors.muted
            canvas.drawLine(x, 0f, x, height - 20 * d, paint)
            canvas.drawText("${timeLabel(hover.time)}  开 ${Prices.format(hover.open)}  收 ${Prices.format(hover.close)}", 0f, height - 4 * d, paint)
        } else {
            canvas.drawText(timeLabel(candles.first().time), 0f, height - 4 * d, paint)
            paint.textAlign = Paint.Align.RIGHT
            canvas.drawText(timeLabel(last.time), width.toFloat(), height - 4 * d, paint)
            paint.textAlign = Paint.Align.CENTER; paint.textSize = 8 * d
            canvas.drawText("最近 60 根 · 含未收盘", width / 2f, height - 4 * d, paint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (candles.isEmpty()) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val chartWidth = (width - 66 * d).coerceAtLeast(1f)
                hovered = ((event.x / chartWidth) * candles.size).toInt().coerceIn(0, candles.lastIndex)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                hovered = null; parent?.requestDisallowInterceptTouchEvent(false)
                if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
            }
        }
        invalidate(); return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
}
