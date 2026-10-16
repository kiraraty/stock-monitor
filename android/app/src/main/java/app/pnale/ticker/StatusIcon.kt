package app.pnale.ticker

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Icon

object StatusIcon {
    // Several coins may each own an icon, so keep a small cache instead of the last one only.
    private val cache = object : LinkedHashMap<String, Icon>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Icon>?) = size > 12
    }
    fun label(state: MarketState, coin: String): String {
        val quote = state.quotes[coin + "USDT"] ?: return "—"
        return if (quote.stale() || state.status != "实时推送") "!" else StatusLabel.price(quote.price)
    }
    fun create(state: MarketState, mode: String, font: String = "bold"): Icon =
        draw(if (mode == "symbol") state.selected else label(state, state.selected), mode, font)
    fun coin(state: MarketState, coin: String, font: String = "bold"): Icon = draw(label(state, coin), "price", font)
    private fun draw(label: String, mode: String, font: String): Icon {
        val key = "$mode:$font:$label"
        cache[key]?.let { return it }
        val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; typeface = Typography.face(font)
            textAlign = Paint.Align.CENTER; textSize = 72f
        }
        fun drawLine(text: String, centerY: Float, maxSize: Float) {
            paint.textSize = maxSize
            paint.textSize = minOf(maxSize, maxSize * 92f / paint.measureText(text).coerceAtLeast(1f))
            canvas.drawText(text, 48f, centerY - (paint.fontMetrics.ascent + paint.fontMetrics.descent) / 2, paint)
        }
        if (label.length > 4 && label.contains('.')) {
            val parts = label.split('.', limit = 2)
            drawLine(parts[0], 28f, 50f)
            drawLine("." + parts[1], 72f, 42f)
        } else if (mode == "symbol" && label.length > 4) {
            val split = (label.length + 1) / 2
            drawLine(label.take(split), 28f, 50f)
            drawLine(label.drop(split), 72f, 42f)
        } else drawLine(label, 48f, 72f)
        return Icon.createWithBitmap(bitmap).also { cache[key] = it }
    }
}
