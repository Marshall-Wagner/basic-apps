package dev.montb.basiccalendar.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.montb.basiccalendar.data.EventStore
import dev.montb.basiccalendar.data.Repeat
import java.time.Instant

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
            // Re-arm from a minute past now, not from now. nextTrigger() skips an occurrence
            // only while it is not still in the future, and AlarmManager can deliver this
            // broadcast a hair early, in which case "now" would re-pick the slot that just rang
            // and fire the event a second time straight away. Events are minute-granular, so
            // stepping forward a minute cannot skip a real occurrence.
            EventScheduler.schedule(context, event, Instant.now().plusSeconds(60))
        }
    }

    companion object {
        const val ACTION_FIRE = "dev.montb.basiccalendar.action.FIRE"
        const val EXTRA_ID = "event_id"
    }
}
