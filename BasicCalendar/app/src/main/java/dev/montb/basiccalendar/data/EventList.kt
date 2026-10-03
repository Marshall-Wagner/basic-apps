package dev.montb.basiccalendar.data

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Ordering and summarising for the event list. Pure logic, no Android, so it is unit-tested.
 *
 * Events are ordered by the calendar: by when each one actually HAPPENS. That is deliberately not
 * [CalendarEvent.nextTrigger], which is when the reminder rings. nextTrigger is null for an event
 * that is switched off, so ordering by it dropped every switched-off event to the bottom whatever
 * its date, and it runs 30 or 60 minutes early for an event with a reminder lead, so it could put
 * a 10:00 event ahead of a 09:30 one.
 */
object EventList {

    /** Does [event] have an occurrence exactly on [date]? Mirrors [CalendarEvent.nextTrigger]'s
     *  recurrence rules (repeats never slide onto a date the day-of-month/month doesn't allow). */
    fun occursOn(event: CalendarEvent, date: LocalDate): Boolean {
        val anchor = event.anchorDate ?: return false
        return when (event.repeat) {
            Repeat.NONE -> date == anchor
            Repeat.WEEKLY -> !date.isBefore(anchor) && date.dayOfWeek == anchor.dayOfWeek
            Repeat.MONTHLY -> !date.isBefore(anchor) && date.dayOfMonth == event.day
            Repeat.YEARLY -> !date.isBefore(anchor) &&
                date.monthValue == event.month && date.dayOfMonth == event.day
        }
    }

    /**
     * The next time [event] starts: its own wall-clock time, not the earlier reminder, and
     * whether or not it is switched on. Null once a one-off is in the past.
     *
     * Built on nextTrigger with the lead removed and the event forced on, so it follows exactly
     * the same recurrence rules (and their tests) rather than a second copy of them.
     */
    fun nextStart(event: CalendarEvent, now: Instant = Instant.now()): Instant? =
        event.copy(enabled = true, leadMinutes = 0).nextTrigger(now)

    /** When [event] starts on [date], or null if its stored time or zone is unusable. */
    fun startOn(event: CalendarEvent, date: LocalDate): Instant? {
        val zone = runCatching { ZoneId.of(event.zoneId) }.getOrNull() ?: return null
        val time = runCatching { LocalTime.of(event.hour, event.minute) }.getOrNull() ?: return null
        return date.atTime(time).atZone(zone).toInstant()
    }

    /**
     * The date to show on [event]'s row in the upcoming list: its next occurrence, or its own
     * date once it is in the past. A repeating event's stored date is only where it STARTED, so
     * showing that would make a correctly ordered list look shuffled.
     */
    fun displayDate(event: CalendarEvent, now: Instant = Instant.now()): LocalDate? {
        val zone = runCatching { ZoneId.of(event.zoneId) }.getOrNull() ?: return event.anchorDate
        return nextStart(event, now)?.atZone(zone)?.toLocalDate() ?: event.anchorDate
    }

    /**
     * Every event in calendar order: those still to come first, soonest first, then the ones
     * already past in date order. Switched-off events keep their place by date; the switch says
     * whether the reminder rings, not whether the event exists.
     */
    fun upcoming(events: List<CalendarEvent>, now: Instant = Instant.now()): List<CalendarEvent> =
        events.map { it to nextStart(it, now) }
            .sortedWith(
                compareBy<Pair<CalendarEvent, Instant?>> { it.second == null }
                    .thenBy { (event, next) ->
                        next ?: event.anchorDate?.let { startOn(event, it) } ?: Instant.MAX
                    }
                    .thenBy { it.first.label }
                    .thenBy { it.first.id }
            )
            .map { it.first }

    /** The events on [date], in the order they happen that day. */
    fun onDay(events: List<CalendarEvent>, date: LocalDate): List<CalendarEvent> =
        events.filter { occursOn(it, date) }
            .sortedWith(
                compareBy<CalendarEvent> { startOn(it, date) ?: Instant.MAX }
                    .thenBy { it.label }
                    .thenBy { it.id }
            )

    /** The next switched-on event and when it starts, or null when nothing is coming up. */
    fun next(events: List<CalendarEvent>, now: Instant = Instant.now()): Pair<CalendarEvent, Instant>? =
        events.asSequence()
            .filter { it.enabled }
            .mapNotNull { event -> nextStart(event, now)?.let { event to it } }
            .minByOrNull { it.second }

    /** How many events are switched on. */
    fun enabledCount(events: List<CalendarEvent>): Int = events.count { it.enabled }
}
