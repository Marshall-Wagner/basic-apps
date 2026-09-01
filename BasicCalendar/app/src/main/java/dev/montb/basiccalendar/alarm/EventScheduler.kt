package dev.montb.basiccalendar.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import dev.montb.basiccalendar.data.CalendarEvent
import dev.montb.basiccalendar.data.EventStore
import dev.montb.basiccalendar.ui.MainActivity

/**
 * Arms/cancels events via [AlarmManager.setAlarmClock], the exact, Doze-exempt API that also
 * shows the status-bar alarm icon and needs no SCHEDULE_EXACT_ALARM permission (USE_EXACT_ALARM
 * is auto-granted). While waiting, the app runs nothing; the OS holds the schedule and wakes
 * [EventReceiver] at the event's exact instant.
 */
object EventScheduler {

    private fun firePendingIntent(context: Context, event: CalendarEvent): PendingIntent {
        val intent = Intent(context, EventReceiver::class.java)
            .setAction(EventReceiver.ACTION_FIRE)
            .putExtra(EventReceiver.EXTRA_ID, event.id)
        return PendingIntent.getBroadcast(
            context, event.requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun schedule(context: Context, event: CalendarEvent) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        // A silent event (notify = false) shows on the calendar but arms no alarm / notification.
        if (!event.notify) { cancel(context, event); return }
        val trigger = event.nextTrigger() ?: run { cancel(context, event); return }
        // Tapping the status-bar alarm icon opens the app.
        val show = PendingIntent.getActivity(
            context, event.requestCode,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        // Guard against ROMs that still deny exact alarms despite USE_EXACT_ALARM, a no-op
        // beats a crash. On normal devices USE_EXACT_ALARM is auto-granted so this succeeds.
        runCatching {
            am.setAlarmClock(
                AlarmManager.AlarmClockInfo(trigger.toEpochMilli(), show),
                firePendingIntent(context, event)
            )
        }
    }

    fun cancel(context: Context, event: CalendarEvent) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        am.cancel(firePendingIntent(context, event))
    }

    /** Re-fire this event [minutes] from now (Snooze), independent of its normal schedule. */
    fun snooze(context: Context, event: CalendarEvent, minutes: Int = 9) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val show = PendingIntent.getActivity(
            context, event.requestCode,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val at = System.currentTimeMillis() + minutes * 60_000L
        runCatching {
            am.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), firePendingIntent(context, event))
        }
    }

    /** Re-arm every stored event (after reboot, or a clock / time-zone change). */
    fun rescheduleAll(context: Context) {
        for (event in EventStore.getAll(context)) {
            if (event.enabled) schedule(context, event) else cancel(context, event)
        }
    }
}
