package app.pnale.ticker

import org.junit.Assert.*
import org.junit.Test

class ServerAlarmsTest {
    @Test fun parsesOnlyUserRuleEventsOldestFirst() {
        val body = """{"events":[
            {"id":"user_rule:r1:9","kind":"user_rule","title":"BTC 短时急跌","observed_at_ms":200,"details":{"rule_id":"r1","detail":"1 分钟下跌 1.20%"}},
            {"id":"x","kind":"rapid_drop","title":"BTCUSDT rapid_drop","observed_at_ms":150,"details":{}},
            {"id":"user_rule:r2:3","kind":"user_rule","title":"ETH 突破价格","observed_at_ms":100,"details":{"rule_id":"r2","detail":"现价 3000"}}],"order":"newest_first"}"""
        val events = ServerAlarms.parseEvents(body)
        assertEquals(listOf("r2", "r1"), events.map { it.ruleId })
        assertEquals("1 分钟下跌 1.20%", events[1].detail)
    }
    @Test fun presetJsonMatchesServerRuleFields() {
        // The server rejects unknown fields, so the phone's rule JSON must keep exactly this shape.
        val keys = AlarmPreset().json().keys().asSequence().toSet()
        assertEquals(setOf("id", "name", "coin", "kind", "value", "minutes", "enabled", "duration", "sound", "vibration", "repeat", "cooldownMinutes", "lastFired"), keys)
    }
}
