package app.pnale.ticker

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.SystemClock
import android.view.View
import kotlin.math.sin

/** Same rendering for screensaver, desk and the live style preview. */
class MarketBoard(context: Context) : View(context) {
    private val prefs = (context.applicationContext as PnaleApp).preferences
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var state: MarketState? = null
    private var active = false
    private val tick = object : Runnable {
        override fun run() { if (active) { invalidate(); postDelayed(this, 1_000) } }
    }
    fun render(value: MarketState) { state = value; invalidate() }
    fun start() { if (!active) { active = true; post(tick) } }
    fun stop() { active = false; removeCallbacks(tick) }
    override fun onDetachedFromWindow() { stop(); super.onDetachedFromWindow() }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val style = prefs.boardStyle
        canvas.drawColor(if (style == "terminal") Color.rgb(6, 15, 14) else Color.BLACK)
        if (style == "glass") {
            paint.shader = android.graphics.LinearGradient(0f, 0f, width.toFloat(), height.toFloat(), intArrayOf(0xFF0A182B.toInt(), 0xFF1C3540.toInt(), 0xFF151127.toInt()), null, android.graphics.Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            paint.shader = null
        }
        val snapshot = state ?: return
        val ordered = listOf(snapshot.selected) + snapshot.coins.filter { it != snapshot.selected }
        val coins = ordered.take(when (prefs.boardLayout) { "single" -> 1; "grid" -> 4; else -> 2 })
        val columns = if (prefs.boardLayout in listOf("grid", "row") && coins.size > 1) 2 else 1
        val rows = (coins.size + columns - 1) / columns
        val cellWidth = width.toFloat() / columns
        val unit = minOf(cellWidth / 390f, height / (if (rows > 1) 600f else 330f))
        val gap = minOf(height / rows.toFloat() * .8f, 220 * unit)
        val drift = sin(SystemClock.elapsedRealtime() / 80_000.0).toFloat() * 8 * unit
        val accent = DisplayStyle.accent(prefs.boardPalette)
        coins.forEachIndexed { index, coin ->
            val center = cellWidth * (index % columns + .5f) + drift
            val y = height / 2f + (index / columns - (rows - 1) / 2f) * gap
            val quote = snapshot.quotes[coin + "USDT"]
            val stale = quote == null || quote.stale() || snapshot.status != "实时推送"
            val cardHalf = minOf(gap * .44f, 96 * unit)
            if (style == "glass") {
                val left = center - cellWidth / 2 + 18 * unit
                val right = center + cellWidth / 2 - 18 * unit
                val top = y - cardHalf
                val bottom = y + cardHalf
                paint.shader = android.graphics.LinearGradient(left, top, right, bottom, intArrayOf(0x40FFFFFF, 0x126DADCB, 0x283B477F), null, android.graphics.Shader.TileMode.CLAMP)
                canvas.drawRoundRect(left, top, right, bottom, 26 * unit, 26 * unit, paint)
                paint.shader = android.graphics.LinearGradient(left, top, right, bottom, 0xB0FFFFFF.toInt(), 0x12FFFFFF, android.graphics.Shader.TileMode.CLAMP)
                paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.2f * unit
                canvas.drawRoundRect(left, top, right, bottom, 26 * unit, 26 * unit, paint)
                paint.style = Paint.Style.FILL; paint.shader = null
            }
            if (style == "cards") {
                paint.color = Color.rgb(19, 24, 32)
                canvas.drawRoundRect(center - cellWidth / 2 + 18 * unit, y - cardHalf, center + cellWidth / 2 - 18 * unit, y + cardHalf, 18 * unit, 18 * unit, paint)
            }
            paint.textAlign = Paint.Align.CENTER; paint.typeface = Typeface.DEFAULT
            paint.textSize = 15 * unit; paint.color = if (style == "terminal") accent else Colors.muted
            if (prefs.boardNames) canvas.drawText(if (style == "terminal") "$coin / USDT" else "$coin · USDT 永续", center, y - 56 * unit, paint)
            paint.typeface = Typography.face(prefs.boardFont)
            val price = quote?.let { StatusLabel.price(it.price) } ?: "—"
            paint.textSize = 64 * unit * prefs.boardSize / 100f
            val available = cellWidth - 54 * unit
            if (paint.measureText(price) > available) paint.textSize *= available / paint.measureText(price)
            // Keep metadata separate even with the largest text size.
            paint.textSize = minOf(paint.textSize, 76 * unit)
            paint.color = if (stale) Colors.orange else if (prefs.boardDirection) Colors.direction(quote!!.change) else accent
            canvas.drawText(price, center, y + 16 * unit, paint)
            paint.typeface = Typeface.DEFAULT; paint.textSize = 13 * unit
            paint.color = if (stale) Colors.orange else Colors.direction(quote!!.change)
            if (stale || prefs.boardChanges) {
                val detail = if (stale) "${snapshot.displayStatus()} · ${if (quote == null) "等待报价" else "报价已过期"}" else "24h  ${Prices.change(quote!!.change)}"
                // Long reconnect labels also fit in the grid.
                if (paint.measureText(detail) > available) paint.textSize *= available / paint.measureText(detail)
                canvas.drawText(detail, center, y + 49 * unit, paint)
            }
        }
    }
}
