package dev.montb.basicclock.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * AlarmManager forgets alarms on reboot, and a clock / time-zone change can move an
 * alarm's next instant, so re-arm everything on BOOT_COMPLETED, TIME_SET, and
 * TIMEZONE_CHANGED.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AlarmScheduler.rescheduleAll(context)
    }
}
