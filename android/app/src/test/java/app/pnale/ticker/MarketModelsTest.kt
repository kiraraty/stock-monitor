package app.pnale.ticker

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MarketModelsTest {
    private fun candle(time: Long, close: Double = 11.0) = Candle(time, 10.0, 12.0, 9.0, close, 100.0)

    @Test fun typedCoinsNormalizeAndKeepOrder() {
        assertEquals(listOf("ETH", "BTC", "SOL", "1000PEPE"), WatchlistInput.parse("eth，BTCUSDT\nSOL/USDT eth;1000pepe"))
    }
    @Test fun badWatchlistsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { WatchlistInput.parse(" , \n") }
        assertThrows(IllegalArgumentException::class.java) { WatchlistInput.parse("USDT") }
        assertThrows(IllegalArgumentException::class.java) { WatchlistInput.validate("ETH, FAKE", setOf("ETH", "BTC")) }
        assertEquals(listOf("BTC", "ETH"), WatchlistInput.validate("btc eth", setOf("ETH", "BTC")))
    }
    @Test fun onlyTradingUSDTPerpetualsAreAvailable() {
        val json = JSONObject("""{"symbols":[
            {"symbol":"ETHUSDT","contractType":"PERPETUAL","status":"TRADING","quoteAsset":"USDT","marginAsset":"USDT"},
            {"symbol":"1000PEPEUSDT","contractType":"PERPETUAL","status":"TRADING","quoteAsset":"USDT","marginAsset":"USDT"},
            {"symbol":"XAUUSDT","contractType":"TRADIFI_PERPETUAL","status":"TRADING","quoteAsset":"USDT","marginAsset":"USDT"},
            {"symbol":"BTCUSDT_260925","contractType":"CURRENT_QUARTER","status":"TRADING","quoteAsset":"USDT","marginAsset":"USDT"},
            {"symbol":"OLDUSDT","contractType":"PERPETUAL","status":"SETTLING","quoteAsset":"USDT","marginAsset":"USDT"},
            {"symbol":"ETHUSDC","contractType":"PERPETUAL","status":"TRADING","quoteAsset":"USDC","marginAsset":"USDC"}
        ]}""")
        assertEquals(setOf("ETH", "1000PEPE", "XAU"), WatchlistInput.catalog(json))
    }
    @Test fun openBarIsReplacedAndWindowRolls() {
        val previous = (0..59).map { candle(it * 60_000L) }
        val updated = Candle.merge(previous, listOf(candle(59 * 60_000L, 12.0)))
        assertEquals(60, updated.size); assertEquals(12.0, updated.last().close, 0.0)
        val next = Candle.merge(updated, listOf(candle(60 * 60_000L)))
        assertEquals(60, next.size); assertEquals(60_000L, next.first().time)
    }
    @Test fun outOfOrderBarsAreSortedAndDeduplicated() {
        val merged = Candle.merge(listOf(candle(120_000)), listOf(candle(0), candle(60_000), candle(60_000)))
        assertEquals(listOf(0L, 60_000L, 120_000L), merged.map { it.time })
    }
    @Test fun invalidOHLCAndNonFiniteValuesFail() {
        assertThrows(IllegalArgumentException::class.java) { Candle(0, 10.0, 8.0, 9.0, 11.0, 1.0) }
        assertThrows(IllegalArgumentException::class.java) { finite("NaN") }
        assertThrows(IllegalArgumentException::class.java) { finite("Infinity") }
    }
    @Test fun actualRESTAndWSFieldsDecode() {
        val quote = Quote.parse(JSONObject("""{"s":"ETHUSDT","c":"2500.1","P":"-1.2","h":"2600","l":"2400","q":"12345","E":1000000}"""), true)
        assertEquals("ETHUSDT", quote.symbol); assertEquals(-1.2, quote.change, 0.0)
        assertFalse(quote.stale(1_014_000)); assertTrue(quote.stale(1_016_000))
        val rest = Candle.parse(JSONArray("""[60000,"10","12","9","11","100"]"""))
        val stream = Candle.parse(JSONObject("""{"t":60000,"o":"10","h":"12","l":"9","c":"11","v":"100"}"""))
        assertEquals(rest, stream)
    }
    @Test fun tinyCoinPriceDoesNotBecomeZero() { assertEquals("0.000001", Prices.format(0.000001).trimEnd('0')) }
}
