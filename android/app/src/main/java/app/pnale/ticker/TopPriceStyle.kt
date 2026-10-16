package app.pnale.ticker

import android.graphics.Color
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.util.TypedValue
import android.widget.TextView

object TopPriceStyle {
    /** Status bar icons stay capped: each extra coin is a separate notification. */
    const val MAX_ICONS = 6
    fun parseColor(value: String): Int? = value.trim().removePrefix("#").takeIf { it.matches(Regex("[0-9a-fA-F]{6}")) }?.toLongOrNull(16)?.toInt()?.or(0xFF000000.toInt())
    fun hex(color: Int): String = String.format(java.util.Locale.US, "#%06X", color and 0xFFFFFF)
    fun coins(prefs: Preferences, state: MarketState): List<String> = prefs.barCoins.ifEmpty { listOf(state.selected) }
    /** More coins than fit on one screen rotate in pages of topPageSize. */
    fun pages(prefs: Preferences, state: MarketState): List<List<String>> = coins(prefs, state).chunked(prefs.topPageSize)
    fun page(prefs: Preferences, state: MarketState, index: Int): List<String> = pages(prefs, state).let { it[index.mod(it.size)] }
    // Symbols are needed whenever more than one coin can appear, including single-coin rotating pages.
    private fun symbols(prefs: Preferences, state: MarketState) = prefs.topSymbols && coins(prefs, state).size > 1
    fun price(state: MarketState, coin: String): String = state.quotes[coin + "USDT"]?.let {
        if (it.stale()) "!" else if (state.status == "HTTP 兜底") "~${StatusLabel.price(it.price)}" else if (state.status == "实时推送") StatusLabel.price(it.price) else "!"
    } ?: "—"
    /** One line for every bar coin; with direction colors each coin follows its own 24h change. */
    fun label(prefs: Preferences, state: MarketState, sample: String? = null, page: Int = 0): CharSequence {
        val coins = page(prefs, state, page)
        val out = SpannableStringBuilder()
        coins.forEachIndexed { i, coin ->
            if (i > 0) out.append("  ")
            val start = out.length
            if (symbols(prefs, state)) {
                out.append(coin)
                out.setSpan(RelativeSizeSpan(.72f), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                out.append(' ')
            }
            out.append(price(state, coin).let { if (sample != null && it == "—") sample else it })
            if (prefs.topDirection) out.setSpan(ForegroundColorSpan(Colors.direction(state.quotes[coin + "USDT"]?.change ?: 1.0)), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return out
    }
    /** Window width for the current coins at full text size, reserving room for longer prices. */
    /** Width of the widest page, so the window stays put while pages rotate. */
    fun width(view: TextView, prefs: Preferences, state: MarketState): Int {
        val paint = TextPaint(view.paint).apply {
            typeface = Typography.face(prefs.topFont)
            textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, prefs.topTextSize.toFloat(), view.resources.displayMetrics)
        }
        val widest = pages(prefs, state).maxOf { page ->
            paint.measureText(page.joinToString("  ") { (if (symbols(prefs, state)) "$it " else "") + "8".repeat(maxOf(8, price(state, it).length)) })
        }
        return maxOf(view.context.dp(150), (widest * 1.05f).toInt() + view.context.dp(12))
    }
    fun apply(view: TextView, prefs: Preferences, state: MarketState? = null) {
        view.setTag(R.id.ui_custom_font, true)
        view.typeface = Typography.face(prefs.topFont)
        view.setAutoSizeTextTypeWithDefaults(TextView.AUTO_SIZE_TEXT_TYPE_NONE)
        view.textSize = prefs.topTextSize.toFloat()
        // Connectivity is shown by ! / — in the label, never by silently overriding a chosen color.
        view.setTextColor(if (prefs.topDirection) Colors.direction(state?.quote?.change ?: 1.0) else prefs.topColor)
        if (prefs.topShadow) view.setShadowLayer(view.context.dp(2).toFloat(), 0f, 0f, Color.BLACK) else view.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
        // Shrink only if the full decimal price will otherwise be clipped.
        view.setAutoSizeTextTypeUniformWithConfiguration(8, prefs.topTextSize, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
    }
}
