package dev.montb.basiccalendar.data

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.util.UUID

/** How a calendar event recurs. [NONE] fires once and then switches itself off. */
enum class Repeat { NONE, WEEKLY, MONTHLY, YEARLY }

/**
 * An alarm anchored to a specific calendar DATE and wall-clock time *in* a chosen [zoneId]
 * (unlike BasicClock, whose alarms are only a time-of-day). It stores year/month/day +
 * hour/minute in that zone, so [nextTrigger] resolves to the correct absolute instant across
 * DST and even if the phone is in another zone. [repeat] optionally re-arms it.
 */
data class CalendarEvent(
    val id: String = UUID.randomUUID().toString(),
    val year: Int,
    val month: Int,   // 1-12
    val day: Int,     // 1-31
    val hour: Int,
    val minute: Int,
    val zoneId: String,
    val label: String = "",
    val repeat: Repeat = Repeat.NONE,
    /** Minutes before the event to fire the reminder; 0 = at the event's start time. */
    val leadMinutes: Int = 0,
    val enabled: Boolean = true,
    /** Ringtone URI string; null = the system default alarm sound. */
    val soundUri: String? = null
) {
    /** Stable non-negative id for AlarmManager PendingIntents / notifications. */
    val requestCode: Int get() = id.hashCode() and 0x7fffffff

    /** The stored anchor date, or null if the stored numbers aren't a real date. */
    val anchorDate: LocalDate? get() = runCatching { LocalDate.of(year, month, day) }.getOrNull()

    /**
     * The next absolute instant this event should ring at, or null if disabled, invalid, or
     * (for a non-repeating event) already in the past. Repeats skip calendar dates that don't
     * exist (a monthly-on-the-31st only fires in 31-day months; a yearly Feb-29 only in leap
     * years) rather than sliding to a nearby day, so it never fires on a date you didn't pick.
     */
    fun nextTrigger(now: Instant = Instant.now()): Instant? {
        if (!enabled) return null
        val zone = runCatching { ZoneId.of(zoneId) }.getOrNull() ?: return null
        val time = runCatching { LocalTime.of(hour, minute) }.getOrNull() ?: return null
        val anchor = anchorDate ?: return null

        val lead = Duration.ofMinutes(leadMinutes.toLong())
        // The reminder fires [leadMinutes] before the event's wall-clock moment (0 = at start).
        fun triggerOf(date: LocalDate): Instant =
            date.atTime(time).atZone(zone).toInstant().minus(lead)

        return when (repeat) {
            Repeat.NONE -> triggerOf(anchor).takeIf { it.isAfter(now) }

            Repeat.WEEKLY -> {
                var date = anchor
                var guard = 0
                // Same weekday & wall-time; step whole weeks until we pass now.
                while (!triggerOf(date).isAfter(now) && guard++ < 6000) date = date.plusWeeks(1)
                triggerOf(date).takeIf { it.isAfter(now) }
            }

            Repeat.MONTHLY -> {
                var ym = YearMonth.of(anchor.year, anchor.monthValue)
                var guard = 0
                while (guard++ < 1200) {
                    if (day <= ym.lengthOfMonth()) {
                        val inst = triggerOf(ym.atDay(day))
                        if (inst.isAfter(now)) return inst
                    }
                    ym = ym.plusMonths(1)
                }
                null
            }

            Repeat.YEARLY -> {
                var y = anchor.year
                var guard = 0
                while (guard++ < 400) {
                    val d = runCatching { LocalDate.of(y, month, day) }.getOrNull()
                    if (d != null) {
                        val inst = triggerOf(d)
                        if (inst.isAfter(now)) return inst
                    }
                    y++
                }
                null
            }
        }
    }
}
