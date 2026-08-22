package dev.montb.basiccalendar.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.montb.basiccalendar.data.EventStore
import dev.montb.basiccalendar.data.Repeat

/**
 * Woken by AlarmManager at an event's instant. Shows the ringing UI and then keeps the
 * schedule honest: repeating events re-arm their next occurrence; one-offs flip off.
 */
class EventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FIRE) return
        val id = intent.getStringExtra(EXTRA_ID) ?: return
        val event = EventStore.get(context, id) ?: return

        // Start the foreground service that plays the sound + vibration and shows the
        // full-screen notification. (setAlarmClock briefly allowlists us so starting a
        // foreground service from this broadcast is permitted.)
        AlarmService.start(context, event.id)

        if (event.repeat == Repeat.NONE) {
            EventStore.upsert(context, event.copy(enabled = false))  // one-off: done
        } else {
            // nextTrigger() uses isAfter(now), so the occurrence that just fired is skipped
            // and the following one is armed.
            EventScheduler.schedule(context, event)
        }
    }

    companion object {
        const val ACTION_FIRE = "dev.montb.basiccalendar.action.FIRE"
        const val EXTRA_ID = "event_id"
    }
}
