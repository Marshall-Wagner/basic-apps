package dev.montb.basicclock.data

import android.content.Context

/**
 * Persists the single countdown timer's state in private SharedPreferences, so a running (or
 * paused) countdown survives leaving the app. The completion itself is driven by AlarmManager
 * + TimerReceiver, not this store, this only holds enough to redraw the countdown.
 */
object TimerStore {
    private const val FILE = "timer"
    private const val RUNNING = "running"
    private const val END_AT = "endAt"            // wall-clock ms the timer fires (while running)
    private const val PAUSED = "pausedRemaining"  // ms left (while paused)
    private const val LAST = "lastDuration"       // last total set, to prefill the inputs
    private const val DEFAULT_LAST = 5 * 60_000L

    data class State(
        val running: Boolean,
        val endAt: Long,
        val pausedRemaining: Long,
        val lastDuration: Long
    )

    private fun prefs(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun read(c: Context): State = prefs(c).run {
        State(
            running = getBoolean(RUNNING, false),
            endAt = getLong(END_AT, 0L),
            pausedRemaining = getLong(PAUSED, 0L),
            lastDuration = getLong(LAST, DEFAULT_LAST)
        )
    }

    /** Timer armed and counting down to [endAt]. */
    fun setRunning(c: Context, endAt: Long) {
        prefs(c).edit()
            .putBoolean(RUNNING, true).putLong(END_AT, endAt).putLong(PAUSED, 0L).apply()
    }

    /** Timer paused with [remaining] ms left. */
    fun setPaused(c: Context, remaining: Long) {
        prefs(c).edit()
            .putBoolean(RUNNING, false).putLong(END_AT, 0L).putLong(PAUSED, remaining).apply()
    }

    /** No active timer (cancelled or finished); keeps the last-used duration. */
    fun setIdle(c: Context) {
        prefs(c).edit()
            .putBoolean(RUNNING, false).putLong(END_AT, 0L).putLong(PAUSED, 0L).apply()
    }

    /** Remember the last total duration, to prefill the inputs next time. */
    fun setLastDuration(c: Context, duration: Long) {
        prefs(c).edit().putLong(LAST, duration).apply()
    }
}
