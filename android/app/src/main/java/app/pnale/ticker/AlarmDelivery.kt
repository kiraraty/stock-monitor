package app.pnale.ticker

import java.time.LocalTime

data class AlarmDelivery(val sound: Boolean, val vibration: Boolean) {
    val label: String get() = when { sound && vibration -> "铃声＋振动"; sound -> "仅铃声"; vibration -> "仅振动"; else -> "仅通知" }
}

object AlarmSchedule {
    fun isNight(minute: Int, start: Int, end: Int): Boolean {
        require(minute in 0..1439 && start in 0..1439 && end in 0..1439 && start != end)
        return if (start < end) minute >= start && minute < end else minute >= start || minute < end
    }
    fun resolve(mode: String, minute: Int, start: Int, end: Int, sound: Boolean = true, vibration: Boolean = true): AlarmDelivery {
        val mayRing = when (mode) { "ring" -> true; "vibrate" -> false; else -> isNight(minute, start, end) }
        return AlarmDelivery(sound && mayRing, vibration)
    }
    fun label(minutes: Int) = String.format(java.util.Locale.US, "%02d:%02d", minutes / 60, minutes % 60)
}

fun AlarmPreferences.delivery(sound: Boolean = true, vibration: Boolean = true, time: LocalTime = LocalTime.now()): AlarmDelivery =
    AlarmSchedule.resolve(deliveryMode, time.hour * 60 + time.minute, nightStart, nightEnd, sound, vibration)
