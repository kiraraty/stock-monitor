package app.pnale.ticker

/** Plain decimal price: no symbols, unit abbreviations, or exponent letters. */
object StatusLabel {
    fun price(value: Double): String = if (!value.isFinite() || value <= 0) "—" else Prices.format(value).replace(",", "")
}
