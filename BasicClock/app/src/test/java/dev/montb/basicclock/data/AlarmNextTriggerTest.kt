package dev.montb.basicclock.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant

/**
 * Unit tests for [Alarm.nextTrigger]: pure java.time logic, no Android. Every test pins a
 * fixed `now`, so results are deterministic regardless of the clock the tests run under.
 */
class AlarmNextTriggerTest {

    private fun at(iso: String): Instant = Instant.parse(iso)

    @Test
    fun disabledAlarmNeverFires() {
        val alarm = Alarm(hour = 9, minute = 0, zoneId = "UTC", enabled = false)
        assertNull(alarm.nextTrigger(at("2026-01-01T08:00:00Z")))
    }

    @Test
    fun unknownZoneReturnsNull() {
        val alarm = Alarm(hour = 9, minute = 0, zoneId = "Not/AZone")
        assertNull(alarm.nextTrigger(at("2026-01-01T08:00:00Z")))
    }

    @Test
    fun oneShotLaterTodayFiresToday() {
        val alarm = Alarm(hour = 9, minute = 0, zoneId = "UTC")
        assertEquals(at("2026-01-01T09:00:00Z"), alarm.nextTrigger(at("2026-01-01T08:00:00Z")))
    }

    @Test
    fun oneShotAfterItsTimeRollsToTomorrow() {
        val alarm = Alarm(hour = 9, minute = 0, zoneId = "UTC")
        assertEquals(at("2026-01-02T09:00:00Z"), alarm.nextTrigger(at("2026-01-01T10:00:00Z")))
    }

    @Test
    fun weeklyAlarmFindsTheNextMatchingWeekday() {
        // now is Thursday 2026-01-01; a Monday-only alarm lands on 2026-01-05.
        val alarm = Alarm(hour = 7, minute = 0, zoneId = "UTC", days = setOf(DayOfWeek.MONDAY.value))
        assertEquals(at("2026-01-05T07:00:00Z"), alarm.nextTrigger(at("2026-01-01T00:00:00Z")))
    }

    @Test
    fun weeklyAlarmTodayButPastRollsAWholeWeek() {
        // now is Monday 08:00; the 07:00 Monday alarm already passed, so it is next Monday.
        // Exercises the inclusive 0..7 day search (offset 7 catches the same weekday again).
        val alarm = Alarm(hour = 7, minute = 0, zoneId = "UTC", days = setOf(DayOfWeek.MONDAY.value))
        assertEquals(at("2026-01-12T07:00:00Z"), alarm.nextTrigger(at("2026-01-05T08:00:00Z")))
    }

    @Test
    fun wallTimeIsInterpretedInTheAlarmsZoneNotThePhones() {
        // 10:00 in Tokyo (UTC+9) is 01:00 UTC, wherever the phone happens to be.
        val alarm = Alarm(hour = 10, minute = 0, zoneId = "Asia/Tokyo")
        assertEquals(at("2026-01-01T01:00:00Z"), alarm.nextTrigger(at("2026-01-01T00:00:00Z")))
    }

    @Test
    fun springForwardGapResolvesToAValidInstant() {
        // 02:30 on 2026-03-08 does not exist in New York (clocks jump 02:00 -> 03:00); it
        // resolves forward to 03:30 EDT, i.e. 07:30 UTC, rather than being skipped.
        val alarm = Alarm(hour = 2, minute = 30, zoneId = "America/New_York")
        assertEquals(at("2026-03-08T07:30:00Z"), alarm.nextTrigger(at("2026-03-08T06:00:00Z")))
    }
}
