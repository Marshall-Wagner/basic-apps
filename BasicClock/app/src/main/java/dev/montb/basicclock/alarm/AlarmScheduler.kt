package dev.montb.basicclock.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import dev.montb.basicclock.data.Alarm
import dev.montb.basicclock.data.AlarmStore
import dev.montb.basicclock.ui.MainActivity

/**
 * Arms/cancels alarms via [AlarmManager.setAlarmClock], the exact, Doze-exempt API that
 * also shows the status-bar alarm icon and, unlike setExactAndAllowWhileIdle, needs no
 * SCHEDULE_EXACT_ALARM permission. While waiting, the app runs nothing; the OS holds the
 * schedule and wakes [AlarmReceiver] at the exact instant.
 */
object AlarmScheduler {

    private fun firePendingIntent(context: Context, alarm: Alarm): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java)
            .setAction(AlarmReceiver.ACTION_FIRE)
            .putExtra(AlarmReceiver.EXTRA_ID, alarm.id)
        return PendingIntent.getBroadcast(
            context, alarm.requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun schedule(context: Context, alarm: Alarm) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val trigger = alarm.nextTrigger() ?: run { cancel(context, alarm); return }
        // Tapping the status-bar alarm icon opens the app.
        val show = PendingIntent.getActivity(
            context, alarm.requestCode,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        // Guard against ROMs that still deny exact alarms despite USE_EXACT_ALARM, a no-op
        // beats a crash. On normal devices USE_EXACT_ALARM is auto-granted so this succeeds.
        runCatching {
            am.setAlarmClock(
                AlarmManager.AlarmClockInfo(trigger.toEpochMilli(), show),
                firePendingIntent(context, alarm)
            )
        }
    }

    fun cancel(context: Context, alarm: Alarm) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        am.cancel(firePendingIntent(context, alarm))
    }

    /** Re-fire this alarm [minutes] from now (Snooze), independent of its normal schedule. */
    fun snooze(context: Context, alarm: Alarm, minutes: Int = 9) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val show = PendingIntent.getActivity(
            context, alarm.requestCode,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val at = System.currentTimeMillis() + minutes * 60_000L
        runCatching {
            am.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), firePendingIntent(context, alarm))
        }
    }

    /** Re-arm every stored alarm (after reboot, or a clock / time-zone change). */
    fun rescheduleAll(context: Context) {
        for (alarm in AlarmStore.getAll(context)) {
            if (alarm.enabled) schedule(context, alarm) else cancel(context, alarm)
        }
    }
}
