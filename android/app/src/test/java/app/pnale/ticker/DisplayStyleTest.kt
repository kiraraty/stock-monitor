package app.pnale.ticker

import org.junit.Assert.*
import org.junit.Test

class DisplayStyleTest {
    private fun snapshot(time: Long = System.currentTimeMillis(), status: String = "实时推送") = MarketState(
        listOf("ZEC"), "ZEC", "1m", mapOf("ZECUSDT" to Quote("ZECUSDT", 1442.52, -2.31, 1500.0, 1400.0, 1.0, time)), status = status)
    @Test fun roundingNeverTurnsTinyPositivePriceIntoZero() {
        assertEquals("1443", DisplayStyle.chipPrice(1442.52, "0"))
        assertEquals("1000.0", DisplayStyle.chipPrice(999.99, "1"))
        assertEquals(StatusLabel.price(.000001), DisplayStyle.chipPrice(.000001, "0"))
        assertEquals("—", DisplayStyle.chipPrice(Double.NaN, "2"))
    }
    @Test fun symbolAndPriceAreSeparateAndChangeIsExplicit() {
        val state = snapshot()
        assertEquals("ZEC", DisplayStyle.chipLeft(state, "symbol_price"))
        assertEquals("1442.52", DisplayStyle.chipRight(state, "symbol_price", "auto"))
        assertEquals("-2.31%", DisplayStyle.chipRight(state, "price_change", "auto"))
    }
    @Test fun StaleOrReconnectingPricesNeverLookLiveInAnyChipLayout() {
        listOf("symbol_price", "symbol_change", "price_change", "price_only").forEach { layout ->
            assertEquals("!", DisplayStyle.chipRight(snapshot(System.currentTimeMillis() - 30_000), layout, "auto"))
            assertEquals("!", DisplayStyle.chipRight(snapshot(status = "重连中"), layout, "auto"))
        }
    }
}
