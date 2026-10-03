package dev.montb.basicclock.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant

/**
 * Unit tests for [AlarmList]. Pure logic, no Android. Every test pins a fixed `now`, so results
 * do not depend on the clock the tests run under.
 *
 * The point of these is that an armed alarm must never be able to hide. A forgotten one-shot
 * buried among disabled alarms is what produces a 3am surprise, so "enabled sorts first" and
 * "next() finds it" are the properties worth pinning.
 */
class AlarmListTest {

    private fun at(iso: String): Instant = Instant.parse(iso)

    /** 2026-01-01 is a Thursday. */
    private val now = at("2026-01-01T12:00:00Z")

    private fun alarm(
        hour: Int, minute: Int = 0, enabled: Boolean = true,
        label: String = "", days: Set<Int> = emptySet(), id: String = "$hour-$minute-$label"
    ) = Alarm(
        id = id, hour = hour, minute = minute, zoneId = "UTC",
        label = label, days = days, enabled = enabled
    )

    @Test
    fun enabledAlarmsSortAboveDisabledOnes() {
        val off = alarm(6, enabled = false, label = "off")
        val on = alarm(23, enabled = true, label = "on")
        assertEquals(
            listOf("on", "off"),
            AlarmList.sorted(listOf(off, on), now).map { it.label }
        )
    }

    @Test
    fun armedAlarmsSortSoonestFirst() {
        // All later today, so the ordering is purely by when they ring.
        val late = alarm(23, label = "late")
        val soon = alarm(13, label = "soon")
        val mid = alarm(18, label = "mid")
        assertEquals(
            listOf("soon", "mid", "late"),
            AlarmList.sorted(listOf(late, soon, mid), now).map { it.label }
        )
    }

    @Test
    fun anAlarmLaterTodayBeatsOneEarlierInTheDay() {
        // 06:00 has already passed, so it rings tomorrow and must sort AFTER tonight's 23:00.
        // Sorting by wall-clock time alone would get this backwards.
        val morning = alarm(6, label = "tomorrow morning")
        val tonight = alarm(23, label = "tonight")
        assertEquals(
            listOf("tonight", "tomorrow morning"),
            AlarmList.sorted(listOf(morning, tonight), now).map { it.label }
        )
    }

    @Test
    fun aForgottenOneShotCannotHideBelowDisabledAlarms() {
        // The actual hazard: one armed alarm among several disabled ones, added last.
        val clutter = (1..5).map { alarm(it, enabled = false, label = "off$it") }
        val forgotten = alarm(3, label = "test alarm")
        val sorted = AlarmList.sorted(clutter + forgotten, now)
        assertEquals("test alarm", sorted.first().label)
    }

    @Test
    fun anArmedAlarmWithNoUpcomingDaySortsAboveDisabledOnes() {
        // days can be set such that nextTrigger is null; it is still armed, so it must not be
        // demoted into the disabled group where it would look switched off.
        val weird = alarm(9, days = emptySet(), label = "armed").copy(days = setOf(99))
        val off = alarm(8, enabled = false, label = "off")
        assertNull(weird.nextTrigger(now))
        assertEquals(
            listOf("armed", "off"),
            AlarmList.sorted(listOf(off, weird), now).map { it.label }
        )
    }

    @Test
    fun sortingIsStableForOtherwiseIdenticalAlarms() {
        val a = alarm(7, label = "a", id = "a")
        val b = alarm(7, label = "b", id = "b")
        assertEquals(listOf("a", "b"), AlarmList.sorted(listOf(b, a), now).map { it.label })
    }

    @Test
    fun nextFindsTheSoonestArmedAlarm() {
        val later = alarm(20, label = "later")
        val sooner = alarm(14, label = "sooner")
        val disabledButSoonest = alarm(13, enabled = false, label = "off")
        val (soonest, ringsAt) = AlarmList.next(listOf(later, sooner, disabledButSoonest), now)!!
        assertEquals("sooner", soonest.label)
        assertEquals(at("2026-01-01T14:00:00Z"), ringsAt)
    }

    @Test
    fun nextIgnoresDisabledAlarmsEntirely() {
        val off = listOf(alarm(6, enabled = false), alarm(7, enabled = false))
        assertNull(AlarmList.next(off, now))
        assertNull(AlarmList.next(emptyList(), now))
    }

    @Test
    fun nextHandlesRepeatingAlarms() {
        // Thursday 12:00; a Monday-only alarm next rings on the 5th.
        val monday = alarm(9, days = setOf(DayOfWeek.MONDAY.value), label = "mon")
        val (_, ringsAt) = AlarmList.next(listOf(monday), now)!!
        assertEquals(at("2026-01-05T09:00:00Z"), ringsAt)
    }

    @Test
    fun enabledCountCountsOnlyArmedAlarms() {
        val alarms = listOf(
            alarm(6, enabled = true), alarm(7, enabled = false),
            alarm(8, enabled = true), alarm(9, enabled = false)
        )
        assertEquals(2, AlarmList.enabledCount(alarms))
        assertEquals(0, AlarmList.enabledCount(alarms.map { it.copy(enabled = false) }))
        assertEquals(0, AlarmList.enabledCount(emptyList()))
    }

    @Test
    fun sortingNeverLosesOrDuplicatesAnAlarm() {
        val alarms = listOf(
            alarm(6, enabled = false), alarm(23), alarm(1, days = setOf(1, 3)),
            alarm(12, enabled = false), alarm(7)
        )
        val sorted = AlarmList.sorted(alarms, now)
        assertEquals(alarms.size, sorted.size)
        assertTrue(sorted.map { it.id }.toSet() == alarms.map { it.id }.toSet())
    }
}
