package app.pnale.ticker
import org.junit.Assert.*
import org.junit.Test
class PriceAlarmTest {
    private val start = 1_700_000_000_000L
    private fun q(second: Int, price: Double=100.0, coin:String="ETH") = Quote(coin+"USDT",price,-90.0,100.0,50.0,1.0,start+second*1000)
    private val drop = AlarmRule("ETH","drop",2.0,1)
    @Test fun fullWindowRequiredAnd24hChangeIgnored() {
        val m=PriceAlarmMonitor()
        for (i in 0 until 60) assertNull(m.update(drop,q(i),true,q(i).eventTime))
        val end=q(60,97.0);assertNotNull(m.update(drop,end,true,end.eventTime))
    }
    @Test fun shortCrashCannotUsePartialWindow() {
        val m=PriceAlarmMonitor();m.update(drop,q(0),true,start)
        assertNull(m.update(drop,q(1,50.0),true,start+1000))
    }
    @Test fun gapAndDisconnectReset() {
        val m=PriceAlarmMonitor();for(i in 0..59)m.update(drop,q(i),true,q(i).eventTime)
        assertNull(m.update(drop,q(100,50.0),true,q(100).eventTime))
        assertTrue(m.status.contains("0s /"))
        for(i in 101..159)m.update(drop,q(i,50.0),true,q(i).eventTime)
        m.update(drop,null,false,q(160).eventTime)
        assertNull(m.update(drop,q(161,20.0),true,q(161).eventTime))
    }
    @Test fun priceCrossNeedsAboveThenBelow() {
        val m=PriceAlarmMonitor();val r=AlarmRule("ETH","price",95.0,5)
        assertNull(m.update(r,q(0,90.0),true,start))
        assertNull(m.update(r,q(1,96.0),true,start+1000))
        assertNotNull(m.update(r,q(2,94.0),true,start+2000))
        assertNull(m.update(r,q(3,93.0),true,start+3000))
    }
    @Test fun freshRuleDoesNotReuseOldSamples() {
        val m=PriceAlarmMonitor();for(i in 0..59)m.update(drop,q(i),true,q(i).eventTime)
        assertNull(m.update(drop.copy(value=3.0),q(60,50.0),true,q(60).eventTime))
    }
    @Test fun staleFutureWrongCoinAndInvalidRejected() {
        val m=PriceAlarmMonitor()
        assertNull(m.update(drop,q(0),true,start+16000))
        assertNull(m.update(drop,q(10),true,start))
        assertNull(m.update(drop,q(0,100.0,"BTC"),true,start))
        assertNull(m.update(drop,q(0,Double.NaN),true,start))
        assertNull(m.update(drop.copy(kind="bad"),q(0),true,start))
        assertEquals("规则无效",m.status)
    }
    @Test fun outOfOrderAndDuplicateCannotCreateCross() {
        val m=PriceAlarmMonitor();val r=AlarmRule("ETH","price",95.0,5)
        m.update(r,q(10),true,start+10000)
        assertNull(m.update(r,q(9,90.0),true,start+10000))
        assertNull(m.update(r,q(10,90.0),true,start+10000))
        assertNotNull(m.update(r,q(11,90.0),true,start+11000))
    }
    @Test fun baselineIsAtOrBeforeTarget() {
        val m=PriceAlarmMonitor();for(i in 0..58 step 2)m.update(drop,q(i),true,q(i).eventTime)
        assertNotNull(m.update(drop,q(61,97.0),true,q(61).eventTime))
    }

    @Test fun upwardPriceAndReturnRules() {
        val m=PriceAlarmMonitor(); val r=AlarmRule("ETH","above",105.0,1)
        assertNull(m.update(r,q(0,100.0),true,start)); assertNotNull(m.update(r,q(1,106.0),true,start+1000))
        val rise=PriceAlarmMonitor();for(i in 0 until 60)rise.update(drop.copy(kind="rise"),q(i),true,q(i).eventTime)
        assertNotNull(rise.update(drop.copy(kind="rise"),q(60,103.0),true,q(60).eventTime))
    }
    @Test fun independentRulesPersistAndOneShotDisarms() {
        val one=AlarmPreset(name="测试",rule=AlarmRule("ETH","price",95.0,3),enabled=true,sound=false)
        assertEquals(one,AlarmPreset.parse(one.json()))
        assertFalse(one.afterTrigger(start).enabled)
        assertTrue(one.copy(repeat=true).afterTrigger(start).enabled)
        val book=AlarmRuleBook();fun state(q:Quote)=MarketState(listOf("ETH"),"ETH","1m",quotes=mapOf(q.symbol to q),status="实时推送")
        book.evaluate(listOf(one),state(q(0)),start)
        assertEquals(1,book.evaluate(listOf(one),state(q(1,90.0)),start+1000).size)
        assertTrue(book.evaluate(listOf(one.afterTrigger(start+1000)),state(q(2,85.0)),start+2000).isEmpty())
        val repeated=one.copy(repeat=true).afterTrigger(start+1000)
        assertTrue(book.evaluate(listOf(repeated),state(q(3,100.0)),start+3000).isEmpty())
        assertTrue(book.statuses[one.id]!!.contains("冷却"))
    }
}
