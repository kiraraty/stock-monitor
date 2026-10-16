package app.pnale.ticker

import android.graphics.Color
import java.math.BigDecimal
import java.math.RoundingMode

object DisplayStyle {
    fun accent(palette: String): Int = when (palette) {
        "mint" -> Colors.green
        "amber" -> Color.rgb(255, 202, 125)
        "blue" -> Color.rgb(137, 195, 255)
        else -> Color.WHITE
    }
    fun chipRight(state: MarketState, layout: String, decimals: String): String {
        val quote = state.quote ?: return "—"
        if (quote.stale() || state.status != "实时推送") return "!"
        return if (layout.endsWith("change")) Prices.change(quote.change) else chipPrice(quote.price, decimals)
    }
    fun chipLeft(state: MarketState, layout: String): String = when (layout) {
        "symbol_price", "symbol_change" -> state.selected
        "price_change" -> state.quote?.let { if (it.stale() || state.status != "实时推送") "!" else StatusLabel.price(it.price) } ?: "—"
        else -> "∿"
    }
    fun chipPrice(value: Double, decimals: String): String {
        if (!value.isFinite() || value <= 0) return "—"
        val places = decimals.toIntOrNull() ?: return StatusLabel.price(value)
        val rounded = BigDecimal.valueOf(value).setScale(places, RoundingMode.HALF_UP)
        // A tiny positive price must never become a misleading zero.
        return if (rounded.signum() == 0) StatusLabel.price(value) else rounded.toPlainString()
    }
}
