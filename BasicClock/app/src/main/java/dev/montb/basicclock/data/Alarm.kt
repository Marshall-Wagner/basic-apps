package dev.montb.basicclock.data

import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/**
 * An alarm anchored to a specific time-zone region. It stores a wall-clock time
 * ([hour]:[minute]) *in* [zoneId], so [nextTrigger] resolves to the correct absolute
 * instant, automatically right across DST changes and even if the phone travels to
 * another zone.
 *
 * [days] holds `DayOfWeek.value` (1 = Monday … 7 = Sunday). Empty = a one-shot alarm.
 */
data class Alarm(
    val id: String = UUID.randomUUID().toString(),
    val hour: Int,
    val minute: Int,
    val zoneId: String,
    val label: String = "",
    val days: Set<Int> = emptySet(),
    val enabled: Boolean = true,
    /** Ringtone URI string; null = the system default alarm sound. */
    val soundUri: String? = null
) {
    /** Stable non-negative id for AlarmManager PendingIntents / notifications. */
    val requestCode: Int get() = id.hashCode() and 0x7fffffff

    /**
     * The next absolute instant this alarm should ring, or null if it's disabled or (for a
     * repeating alarm) has no matching day. Searches today … +7 days in the alarm's zone for
     * the next occurrence of [hour]:[minute] that is still in the future.
     */
    fun nextTrigger(now: Instant = Instant.now()): Instant? {
        if (!enabled) return null
        val zone = runCatching { ZoneId.of(zoneId) }.getOrNull() ?: return null
        val today = now.atZone(zone).toLocalDate()
        for (offset in 0..7) {
            val date = today.plusDays(offset.toLong())
            if (days.isNotEmpty() && date.dayOfWeek.value !in days) continue
            // atZone resolves DST gaps/overlaps to a valid instant.
            val candidate = date.atTime(hour, minute).atZone(zone).toInstant()
            if (candidate.isAfter(now)) return candidate
        }
        return null
    }
}
