package app.pnale.ticker

import org.junit.Assert.*
import org.junit.Test

class AlarmScheduleTest {
    @Test fun overnightBoundariesAndDaytime() {
        for (minute in listOf(0, 479, 1320, 1439)) assertEquals("minute $minute", AlarmDelivery(true, true), AlarmSchedule.resolve("auto", minute, 1320, 480))
        for (minute in listOf(480, 720, 1319)) assertEquals("minute $minute", AlarmDelivery(false, true), AlarmSchedule.resolve("auto", minute, 1320, 480))
    }
    @Test fun customSameDayWindowUsesStartInclusiveEndExclusive() {
        assertFalse(AlarmSchedule.isNight(359, 360, 600))
        assertTrue(AlarmSchedule.isNight(360, 360, 600))
        assertTrue(AlarmSchedule.isNight(599, 360, 600))
        assertFalse(AlarmSchedule.isNight(600, 360, 600))
    }
    @Test fun manualModesAndRuleOptOuts() {
        assertEquals(AlarmDelivery(false, true), AlarmSchedule.resolve("vibrate", 1380, 1320, 480))
        assertEquals(AlarmDelivery(true, true), AlarmSchedule.resolve("ring", 720, 1320, 480))
        assertEquals(AlarmDelivery(false, false), AlarmSchedule.resolve("ring", 720, 1320, 480, false, false))
        assertEquals(AlarmDelivery(true, false), AlarmSchedule.resolve("auto", 1380, 1320, 480, true, false))
    }
    @Test(expected = IllegalArgumentException::class) fun equalTimesAreRejected() { AlarmSchedule.isNight(500, 480, 480) }
    @Test fun customColorMustBeAnOpaqueSixDigitColor() {
        assertEquals(0xFF12ABEF.toInt(), TopPriceStyle.parseColor(" #12aBef "))
        assertEquals(0xFF000000.toInt(), TopPriceStyle.parseColor("000000"))
        for (invalid in listOf("#12", "#GG00FF", "#00123456", "red", "")) assertNull(TopPriceStyle.parseColor(invalid))
    }
}
