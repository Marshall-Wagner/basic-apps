package dev.montb.basiccalendar.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * AlarmManager forgets events on reboot, and a clock / time-zone change can move an event's
 * next instant, so re-arm everything on BOOT_COMPLETED, TIME_SET, and TIMEZONE_CHANGED.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        EventScheduler.rescheduleAll(context)
    }
}
