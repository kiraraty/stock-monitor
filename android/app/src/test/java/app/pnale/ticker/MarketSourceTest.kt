package app.pnale.ticker

import org.junit.Assert.*
import org.junit.Test

class MarketSourceTest {
    @Test fun gateTickerAndCandleMapToBinanceShapedKeys() {
        val ticker = GateSource.parse("""{"time":1790306070,"time_ms":1790306070410,"channel":"futures.tickers","event":"update","result":[{"contract":"ETH_USDT","last":"2674.69","change_percentage":"-0.3795","volume_24h_quote":"5563326202"}]}""")
        val quote = (ticker.single() as StreamEvent.Ticker).quote
        assertEquals("ETHUSDT", quote.symbol); assertEquals(2674.69, quote.price, 1e-9); assertEquals(-0.3795, quote.change, 1e-9); assertEquals(1790306070410, quote.eventTime)
        val kline = GateSource.parse("""{"time":1790306069,"time_ms":1790306069756,"channel":"futures.candlesticks","event":"update","result":[{"t":1790306040,"c":"2674.69","h":"2675.01","l":"2674.4","o":"2675","n":"1m_ETH_USDT","w":false,"v":63629}]}""").single() as StreamEvent.Kline
        assertEquals("ETHUSDT", kline.symbol); assertEquals("1m", kline.interval); assertEquals(1790306040000, kline.candle.time)
    }
    @Test fun gateKeepAliveAndErrors() {
        assertEquals(listOf(StreamEvent.Alive), GateSource.parse("""{"time":1,"time_ms":1,"channel":"futures.pong","event":"","result":null}"""))
        assertTrue(runCatching { GateSource.parse("""{"channel":"futures.tickers","event":"subscribe","error":{"code":2,"message":"unknown contract"}}""") }.isFailure)
    }
    @Test fun gateRestQuotesFilterToWatchlistAndLookupSkipsDelisting() {
        val quotes = GateSource.quotes("""[{"contract":"BTC_USDT","last":"84232.5","change_percentage":"0.03","high_24h":"84909.2","low_24h":"82828.1","volume_24h_quote":"6473904866"},{"contract":"DOGE_USDT","last":"0.2","change_percentage":"1"}]""", listOf("BTC"), 5)
        assertEquals(listOf("BTCUSDT"), quotes.map { it.symbol }); assertEquals(84909.2, quotes[0].high, 1e-9)
        assertTrue(GateSource.listed(200, """{"name":"XAU_USDT","in_delisting":false}"""))
        assertFalse(GateSource.listed(200, """{"name":"OLD_USDT","in_delisting":true}"""))
        assertFalse(GateSource.listed(400, """{"label":"CONTRACT_NOT_FOUND"}"""))
        assertTrue(runCatching { GateSource.listed(429, "") }.isFailure)
        assertFalse(BinanceSource.listed(502, "upstream HTTP 400"))
        assertTrue(runCatching { BinanceSource.listed(502, "upstream unavailable") }.isFailure)
    }
    @Test fun unknownSourceFallsBackToBinance() { assertSame(BinanceSource, MarketSource.of("coinglass")) }
}

class HtxSourceTest {
    @Test fun mergedAndBatchQuotesUseServerTimeAnd24hOpen() {
        val one = HtxSource.quotes("""{"ch":"market.BTC-USDT.detail.merged","status":"ok","tick":{"close":"84336","open":"84099.4","high":"84532.5","low":"83787.3","trade_turnover":"164086731.9"},"ts":1790486314019}""", listOf("BTC"), 1)
        assertEquals("BTCUSDT", one.single().symbol); assertEquals(1790486314019, one.single().eventTime)
        assertEquals((84336 / 84099.4 - 1) * 100, one.single().change, 1e-9)
        val batch = HtxSource.quotes("""{"status":"ok","ts":5,"ticks":[{"contract_code":"MU-USDT","close":"110","open":"100","high":"111","low":"99","ts":7},{"contract_code":"QNTX-USDT","close":"1","open":"1","high":"1","low":"1","ts":7}]}""", listOf("MU"), 1)
        assertEquals(listOf("MUUSDT"), batch.map { it.symbol }); assertEquals(10.0, batch[0].change, 1e-9); assertEquals(7, batch[0].eventTime)
    }
    @Test fun klinesAreSecondsAscendingAndLookupNeedsListedStatus() {
        val candles = HtxSource.candles("""{"status":"ok","data":[{"id":120,"open":2,"close":2,"high":2,"low":2,"vol":1},{"id":60,"open":1,"close":1.5,"high":1.6,"low":0.9,"vol":3}]}""")
        assertEquals(listOf(60_000L, 120_000L), candles.map { it.time })
        assertTrue(HtxSource.listed(200, """{"status":"ok","data":[{"contract_code":"XAU-USDT","contract_status":1}]}"""))
        assertFalse(HtxSource.listed(200, """{"status":"ok","data":[{"contract_code":"OLD-USDT","contract_status":5}]}"""))
        assertFalse(HtxSource.listed(200, """{"status":"error","err_code":1014,"err_msg":"This contract doesnt exist."}"""))
    }
    @Test fun autoPrefersGateThenRemembersLastGood() {
        assertSame(GateSource, MarketSource.resolve("auto", "unknown"))
        assertSame(HtxSource, MarketSource.resolve("auto", "htx"))
        assertSame(BinanceSource, MarketSource.resolve("binance", "gate"))
    }
}
