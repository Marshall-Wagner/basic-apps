package dev.montb.basicclock.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.montb.basicclock.data.AlarmStore
import java.time.Instant

/**
 * Woken by AlarmManager at an alarm's instant. Shows the ringing UI and then keeps the
 * schedule honest: repeating alarms re-arm their next occurrence; one-shots flip off.
 */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FIRE) return
        val id = intent.getStringExtra(EXTRA_ID) ?: return
        val alarm = AlarmStore.get(context, id) ?: return

        // Start the foreground service that plays the sound + vibration and shows the
        // full-screen alarm notification. (setAlarmClock briefly allowlists us so starting
        // a foreground service from this broadcast is permitted.)
        AlarmService.start(context, alarm.id)

        if (alarm.days.isNotEmpty()) {
            // Re-arm from a minute past now, not from now. Alarms are minute-granular, so this
            // cannot skip a real occurrence, but it does guarantee we never re-pick the slot that
            // just rang, which would fire the alarm a second time straight away.
            AlarmScheduler.schedule(context, alarm, Instant.now().plusSeconds(60))
        } else {
            AlarmStore.upsert(context, alarm.copy(enabled = false))  // one-shot: done
        }
    }

    companion object {
        const val ACTION_FIRE = "dev.montb.basicclock.action.FIRE"
        const val EXTRA_ID = "alarm_id"
    }
}
