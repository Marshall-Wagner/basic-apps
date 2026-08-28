package dev.montb.basiccalendar.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

/**
 * Unit tests for [CalendarEvent.nextTrigger]: pure java.time logic, no Android. Every test
 * pins a fixed `now`, so results don't depend on the clock the tests run under. The recurrence
 * cases guard the promise that a repeat never slides onto a date you didn't pick.
 */
class CalendarEventNextTriggerTest {

    private fun at(iso: String): Instant = Instant.parse(iso)

    private fun event(
        year: Int, month: Int, day: Int, hour: Int, minute: Int,
        zoneId: String = "UTC", repeat: Repeat = Repeat.NONE, enabled: Boolean = true
    ) = CalendarEvent(
        year = year, month = month, day = day, hour = hour, minute = minute,
        zoneId = zoneId, repeat = repeat, enabled = enabled
    )

    @Test
    fun disabledEventNeverFires() {
        assertNull(event(2026, 6, 15, 9, 0, enabled = false).nextTrigger(at("2026-06-01T00:00:00Z")))
    }

    @Test
    fun impossibleDateReturnsNull() {
        // Feb 30 is not a real calendar date.
        assertNull(event(2026, 2, 30, 9, 0).nextTrigger(at("2026-01-01T00:00:00Z")))
    }

    @Test
    fun oneOffInTheFutureFires() {
        assertEquals(
            at("2026-06-15T09:00:00Z"),
            event(2026, 6, 15, 9, 0).nextTrigger(at("2026-06-01T00:00:00Z"))
        )
    }

    @Test
    fun oneOffInThePastDoesNotFire() {
        assertNull(event(2026, 6, 15, 9, 0).nextTrigger(at("2026-07-01T00:00:00Z")))
    }

    @Test
    fun weeklyStepsForwardToTheNextOccurrence() {
        // Anchor Thursday 2026-01-01 08:00; from Jan 10 the next weekly occurrence is Jan 15.
        assertEquals(
            at("2026-01-15T08:00:00Z"),
            event(2026, 1, 1, 8, 0, repeat = Repeat.WEEKLY).nextTrigger(at("2026-01-10T00:00:00Z"))
        )
    }

    @Test
    fun monthlyOnThe31stSkipsShortMonths() {
        // From Feb 1, a monthly-on-the-31st skips February (28 days) straight to March 31.
        assertEquals(
            at("2026-03-31T10:00:00Z"),
            event(2026, 1, 31, 10, 0, repeat = Repeat.MONTHLY).nextTrigger(at("2026-02-01T00:00:00Z"))
        )
    }

    @Test
    fun yearlyOnFeb29OnlyFiresInLeapYears() {
        // Anchored to a leap day; from 2026 the next real Feb 29 is in 2028.
        assertEquals(
            at("2028-02-29T12:00:00Z"),
            event(2024, 2, 29, 12, 0, repeat = Repeat.YEARLY).nextTrigger(at("2026-01-01T00:00:00Z"))
        )
    }

    @Test
    fun wallTimeIsInterpretedInTheEventsZone() {
        // 09:00 in Tokyo (UTC+9) is 00:00 UTC.
        assertEquals(
            at("2026-06-15T00:00:00Z"),
            event(2026, 6, 15, 9, 0, zoneId = "Asia/Tokyo").nextTrigger(at("2026-06-01T00:00:00Z"))
        )
    }

    @Test
    fun springForwardGapResolvesToAValidInstant() {
        // 02:30 on 2026-03-08 does not exist in New York; it resolves to 03:30 EDT = 07:30 UTC.
        assertEquals(
            at("2026-03-08T07:30:00Z"),
            event(2026, 3, 8, 2, 30, zoneId = "America/New_York").nextTrigger(at("2026-03-01T00:00:00Z"))
        )
    }
}
