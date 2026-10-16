package app.pnale.ticker

import org.junit.Assert.*
import org.junit.Test

class ConfigCodecTest {
    @Test fun roundTripAndOlderDefaults() {
        val watchlist = mapOf("coins" to "BTC,ETH,XAU", "topColor" to 0xFF12ABEF.toInt(), "topDirection" to false, "trackingEnabled" to true, "trackingMode" to "top")
        val alarm = mapOf("rules" to "[${AlarmPreset().json()}]", "nightStart" to 1320, "nightEnd" to 480)
        assertEquals(watchlist to alarm, ConfigCodec.decode(ConfigCodec.encode(watchlist, alarm)))
        assertEquals(emptyMap<String, Any>() to emptyMap<String, Any>(), ConfigCodec.decode(ConfigCodec.encode(emptyMap<String, Any>(), emptyMap<String, Any>())))
    }
    @Test fun multiCoinBarAndSeparatePositionsSurviveBackup() {
        val watchlist = mapOf("barCoins" to "BTC,ETH,SOL", "topSymbols" to false, "statusIconMode" to "multi", "floatX" to 40, "floatY" to 300)
        assertEquals(watchlist to emptyMap<String, Any>(), ConfigCodec.decode(ConfigCodec.encode(watchlist, emptyMap<String, Any>())))
        assertTrue(runCatching { ConfigCodec.decode(ConfigCodec.encode(mapOf("barCoins" to "btc;eth"), emptyMap<String, Any>())) }.isFailure)
    }
    @Test fun rejectTypesAndUnsupportedFieldsBeforeRestore() {
        for (bad in listOf(mapOf("trackingEnabled" to "true"), mapOf("topTextSize" to 999), mapOf("topTextSize" to 15.4), mapOf("networkMode" to "unknown"), mapOf("unknown" to "x"))) {
            assertTrue(runCatching { ConfigCodec.decode(ConfigCodec.encode(bad, emptyMap<String, Any>())) }.isFailure)
        }
    }
    @Test fun rejectInvalidRulesAndNightWindow() {
        for (bad in listOf(mapOf("nightStart" to 480, "nightEnd" to 480), mapOf("rules" to "[{\"id\":\"bad\"}]"))) {
            assertTrue(runCatching { ConfigCodec.decode(ConfigCodec.encode(emptyMap<String, Any>(), bad)) }.isFailure)
        }
    }
    @Test fun rejectForeignAndTruncatedFiles() {
        val encoded = ConfigCodec.encode(emptyMap<String, Any>(), emptyMap<String, Any>())
        assertTrue(runCatching { ConfigCodec.decode(encoded.replace("app.pnale.ticker", "other.app")) }.isFailure)
        assertTrue(runCatching { ConfigCodec.decode(encoded.dropLast(1)) }.isFailure)
        assertTrue(runCatching { ConfigCodec.decode("x".repeat(ConfigCodec.MAX_BYTES + 1)) }.isFailure)
    }
    @Test fun futureQuotesAreNotFresh() {
        val quote = Quote("BTCUSDT", 100.0, 0.0, 101.0, 99.0, 20.0, 20000)
        assertTrue(quote.stale(10000)); assertFalse(quote.stale(19000))
    }
}
