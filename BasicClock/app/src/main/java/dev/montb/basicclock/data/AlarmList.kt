package dev.montb.basicclock.data

import java.time.Instant

/**
 * Ordering and summarising for the alarm list. Pure logic, no Android, so it is unit-tested.
 *
 * This exists because an alarm nobody remembers setting is a real hazard: a forgotten one-shot
 * sitting among disabled alarms is invisible in an insertion-ordered list, and the first you know
 * of it is when it rings at 3am. Armed alarms therefore sort to the top, soonest first, and
 * [next] gives the screen a single answer to "what is going to wake me, and when".
 */
object AlarmList {

    /**
     * Armed alarms first and soonest-ringing first, then the disabled ones by time of day.
     *
     * A repeating alarm whose days are all in the past week still counts as armed; it simply has
     * no [Alarm.nextTrigger], so it sorts after those that do rather than being hidden among the
     * disabled ones.
     */
    fun sorted(alarms: List<Alarm>, now: Instant = Instant.now()): List<Alarm> =
        alarms.sortedWith(
            compareBy<Alarm> { !it.enabled }
                .thenBy { it.nextTrigger(now) ?: Instant.MAX }
                .thenBy { it.hour * 60 + it.minute }
                .thenBy { it.label }
                .thenBy { it.id }
        )

    /** The alarm that will ring next and when, or null when nothing is armed. */
    fun next(alarms: List<Alarm>, now: Instant = Instant.now()): Pair<Alarm, Instant>? =
        alarms.asSequence()
            .filter { it.enabled }
            .mapNotNull { alarm -> alarm.nextTrigger(now)?.let { alarm to it } }
            .minByOrNull { it.second }

    /** How many alarms are armed, which is the number that can actually wake you. */
    fun enabledCount(alarms: List<Alarm>): Int = alarms.count { it.enabled }
}
