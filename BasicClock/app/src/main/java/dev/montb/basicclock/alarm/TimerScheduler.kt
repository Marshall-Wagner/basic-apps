package dev.montb.basicclock.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import dev.montb.basicclock.data.TimerStore
import dev.montb.basicclock.ui.MainActivity

/**
 * Arms/cancels the single countdown timer with [AlarmManager.setAlarmClock], the same exact,
 * Doze-exempt, permission-free API the alarms use (setAlarmClock also briefly allowlists us at
 * fire time, so [TimerReceiver] may start the foreground ring service). On completion the
 * receiver reuses the alarm ring service.
 */
object TimerScheduler {
    private const val REQ = 0x7132

    private fun firePendingIntent(c: Context): PendingIntent {
        val i = Intent(c, TimerReceiver::class.java).setAction(TimerReceiver.ACTION_FIRE)
        return PendingIntent.getBroadcast(
            c, REQ, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun arm(c: Context, durationMs: Long) {
        val endAt = System.currentTimeMillis() + durationMs
        TimerStore.setRunning(c, endAt)
        val am = c.getSystemService(AlarmManager::class.java) ?: return
        val show = PendingIntent.getActivity(
            c, REQ, Intent(c, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        runCatching {
            am.setAlarmClock(AlarmManager.AlarmClockInfo(endAt, show), firePendingIntent(c))
        }
    }

    /** Start a fresh countdown of [durationMs] and remember it for next time. */
    fun start(c: Context, durationMs: Long) {
        TimerStore.setLastDuration(c, durationMs)
        arm(c, durationMs)
    }

    /** Pause a running countdown, keeping the remaining time. */
    fun pause(c: Context) {
        val s = TimerStore.read(c)
        if (!s.running) return
        val remaining = (s.endAt - System.currentTimeMillis()).coerceAtLeast(0L)
        cancelAlarm(c)
        TimerStore.setPaused(c, remaining)
    }

    /** Resume a paused countdown. */
    fun resume(c: Context) {
        val remaining = TimerStore.read(c).pausedRemaining
        if (remaining > 0L) arm(c, remaining)
    }

    /** Cancel any active timer. */
    fun cancel(c: Context) {
        cancelAlarm(c)
        TimerStore.setIdle(c)
    }

    /** Called by [TimerReceiver] once it fires: the countdown is done. */
    fun onFired(c: Context) = TimerStore.setIdle(c)

    private fun cancelAlarm(c: Context) {
        c.getSystemService(AlarmManager::class.java)?.cancel(firePendingIntent(c))
    }
}
