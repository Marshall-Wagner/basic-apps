package dev.montb.basiccalendar.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

/**
 * Unit tests for [EventList]. Pure logic, no Android. Every test pins a fixed `now`.
 *
 * The property that matters: events are ordered by when they HAPPEN on the calendar, not by when
 * their reminder rings and not by whether it is switched on.
 */
class EventListTest {

    private fun at(iso: String): Instant = Instant.parse(iso)

    /** Thursday 2026-10-01, noon UTC. */
    private val now = at("2026-10-01T12:00:00Z")

    private fun event(
        year: Int, month: Int, day: Int, hour: Int, minute: Int = 0,
        label: String = "", zoneId: String = "UTC", repeat: Repeat = Repeat.NONE,
        enabled: Boolean = true, leadMinutes: Int = 0, notify: Boolean = true
    ) = CalendarEvent(
        id = label.ifBlank { "$year-$month-$day-$hour-$minute" },
        year = year, month = month, day = day, hour = hour, minute = minute,
        zoneId = zoneId, label = label, repeat = repeat,
        enabled = enabled, leadMinutes = leadMinutes, notify = notify
    )

    private fun labels(events: List<CalendarEvent>) = events.map { it.label }

    // --- upcoming ---

    @Test
    fun upcomingIsInCalendarOrder() {
        val later = event(2026, 10, 20, 9, label = "20th")
        val soon = event(2026, 10, 2, 9, label = "2nd")
        val mid = event(2026, 10, 9, 9, label = "9th")
        assertEquals(listOf("2nd", "9th", "20th"), labels(EventList.upcoming(listOf(later, soon, mid), now)))
    }

    @Test
    fun aSwitchedOffEventKeepsItsPlaceByDate() {
        // The old order used nextTrigger, which is null when switched off, so this sank to the
        // bottom below an event two weeks later. Its date has not moved, so neither should it.
        val off = event(2026, 10, 3, 9, label = "off, 3rd", enabled = false)
        val on = event(2026, 10, 17, 9, label = "on, 17th")
        assertEquals(listOf("off, 3rd", "on, 17th"), labels(EventList.upcoming(listOf(on, off), now)))
    }

    @Test
    fun orderingIsByEventTimeNotReminderTime() {
        // A 10:00 event with an hour's reminder rings at 09:00, before a 09:30 event with none,
        // but on the calendar the 09:30 one comes first.
        val withLead = event(2026, 10, 5, 10, label = "10:00, 1h reminder", leadMinutes = 60)
        val plain = event(2026, 10, 5, 9, 30, label = "09:30")
        assertEquals(
            listOf("09:30", "10:00, 1h reminder"),
            labels(EventList.upcoming(listOf(withLead, plain), now))
        )
    }

    @Test
    fun silentEventsAreOrderedLikeAnyOther() {
        val silent = event(2026, 10, 4, 9, label = "silent", notify = false)
        val ringing = event(2026, 10, 6, 9, label = "ringing")
        assertEquals(listOf("silent", "ringing"), labels(EventList.upcoming(listOf(ringing, silent), now)))
    }

    @Test
    fun repeatingEventsSortByTheirNextOccurrenceNotTheirStartDate() {
        // Started in January, but the next occurrence is next Monday, ahead of a one-off later on.
        val weekly = event(2026, 1, 5, 9, label = "weekly Mon", repeat = Repeat.WEEKLY)
        val oneOff = event(2026, 10, 10, 9, label = "10th")
        assertEquals(listOf("weekly Mon", "10th"), labels(EventList.upcoming(listOf(oneOff, weekly), now)))
    }

    @Test
    fun pastEventsComeAfterEverythingUpcomingInDateOrder() {
        val past2 = event(2026, 9, 20, 9, label = "past Sep 20", enabled = false)
        val past1 = event(2026, 9, 1, 9, label = "past Sep 1", enabled = false)
        val future = event(2026, 12, 25, 9, label = "Christmas")
        assertEquals(
            listOf("Christmas", "past Sep 1", "past Sep 20"),
            labels(EventList.upcoming(listOf(past2, future, past1), now))
        )
    }

    @Test
    fun eventsInDifferentZonesAreOrderedByTheRealMoment() {
        // 09:00 Tokyo on the 5th is 00:00 UTC; 06:00 UTC that day is six hours later.
        val tokyo = event(2026, 10, 5, 9, label = "Tokyo 09:00", zoneId = "Asia/Tokyo")
        val utc = event(2026, 10, 5, 6, label = "UTC 06:00")
        assertEquals(listOf("Tokyo 09:00", "UTC 06:00"), labels(EventList.upcoming(listOf(utc, tokyo), now)))
    }

    // --- one day ---

    @Test
    fun aDayIsInTimeOrder() {
        val evening = event(2026, 10, 5, 19, label = "19:00")
        val morning = event(2026, 10, 5, 8, label = "08:00")
        val noon = event(2026, 10, 5, 12, label = "12:00")
        val otherDay = event(2026, 10, 6, 7, label = "next day")
        assertEquals(
            listOf("08:00", "12:00", "19:00"),
            labels(EventList.onDay(listOf(evening, otherDay, morning, noon), LocalDate.of(2026, 10, 5)))
        )
    }

    @Test
    fun aDayIncludesRepeatsThatFallOnIt() {
        val weekly = event(2026, 9, 7, 18, label = "weekly Mon", repeat = Repeat.WEEKLY)
        val sameDay = event(2026, 10, 5, 9, label = "one-off")
        assertEquals(
            listOf("one-off", "weekly Mon"),
            labels(EventList.onDay(listOf(weekly, sameDay), LocalDate.of(2026, 10, 5)))
        )
    }

    @Test
    fun occursOnFollowsTheRecurrenceRules() {
        val monthly31 = event(2026, 1, 31, 9, repeat = Repeat.MONTHLY)
        assertTrue(EventList.occursOn(monthly31, LocalDate.of(2026, 3, 31)))
        assertFalse(EventList.occursOn(monthly31, LocalDate.of(2026, 4, 30)))   // never slides
        assertFalse(EventList.occursOn(monthly31, LocalDate.of(2025, 12, 31)))  // before the start
    }

    // --- display date, next, count ---

    @Test
    fun aRepeatingEventDisplaysItsNextOccurrence() {
        val weekly = event(2026, 1, 5, 9, repeat = Repeat.WEEKLY)       // Mondays from January
        assertEquals(LocalDate.of(2026, 10, 5), EventList.displayDate(weekly, now))
        val past = event(2026, 9, 1, 9)
        assertEquals(LocalDate.of(2026, 9, 1), EventList.displayDate(past, now))
    }

    @Test
    fun nextIsTheSoonestSwitchedOnEventByStartTime() {
        val offButSooner = event(2026, 10, 2, 9, label = "off", enabled = false)
        val later = event(2026, 10, 9, 9, label = "9th")
        val sooner = event(2026, 10, 4, 9, label = "4th")
        val (soonest, startsAt) = EventList.next(listOf(offButSooner, later, sooner), now)!!
        assertEquals("4th", soonest.label)
        assertEquals(at("2026-10-04T09:00:00Z"), startsAt)
    }

    @Test
    fun nextReportsTheStartNotTheReminder() {
        val withLead = event(2026, 10, 4, 10, label = "lead", leadMinutes = 60)
        assertEquals(at("2026-10-04T10:00:00Z"), EventList.next(listOf(withLead), now)!!.second)
    }

    @Test
    fun nextIsNullWhenNothingIsComingUp() {
        assertNull(EventList.next(emptyList(), now))
        assertNull(EventList.next(listOf(event(2026, 10, 9, 9, enabled = false)), now))
        assertNull(EventList.next(listOf(event(2026, 9, 1, 9)), now))   // past
    }

    @Test
    fun enabledCountCountsOnlySwitchedOnEvents() {
        val events = listOf(
            event(2026, 10, 2, 9, label = "a"), event(2026, 10, 3, 9, label = "b", enabled = false),
            event(2026, 10, 4, 9, label = "c")
        )
        assertEquals(2, EventList.enabledCount(events))
    }

    @Test
    fun orderingNeverLosesOrDuplicatesAnEvent() {
        val events = listOf(
            event(2026, 10, 2, 9, label = "a"), event(2026, 9, 1, 9, label = "b", enabled = false),
            event(2026, 1, 5, 9, label = "c", repeat = Repeat.WEEKLY),
            event(2026, 10, 2, 9, label = "d", notify = false)
        )
        val sorted = EventList.upcoming(events, now)
        assertEquals(events.size, sorted.size)
        assertEquals(events.map { it.id }.toSet(), sorted.map { it.id }.toSet())
    }
}
