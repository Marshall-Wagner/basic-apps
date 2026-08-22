package dev.montb.basiccalendar.ui

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.montb.basiccalendar.alarm.AlarmNotifier
import dev.montb.basiccalendar.alarm.AlarmService
import dev.montb.basiccalendar.alarm.EventScheduler
import dev.montb.basiccalendar.data.CalendarEvent
import dev.montb.basiccalendar.util.Zones
import java.time.format.DateTimeFormatter

/**
 * The full-screen event UI shown over the lock screen. The sound and vibration are produced
 * by [AlarmService]; this screen only shows the event and its Dismiss / Snooze buttons, which
 * stop the service.
 */
class RingActivity : ComponentActivity() {

    private var event: CalendarEvent? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }

        event = intent.getStringExtra(EXTRA_ID)?.let {
            dev.montb.basiccalendar.data.EventStore.get(this, it)
        }

        setContent {
            BasicCalendarTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    RingScreen(
                        event = event,
                        onDismiss = { stopAlarm(); finish() },
                        onSnooze = {
                            stopAlarm()
                            event?.let { EventScheduler.snooze(this, it) }
                            finish()
                        }
                    )
                }
            }
        }
    }

    private fun stopAlarm() {
        AlarmService.stop(this)
        AlarmNotifier.dismiss(this)
    }

    companion object {
        const val EXTRA_ID = "event_id"
    }
}

@Composable
private fun RingScreen(event: CalendarEvent?, onDismiss: () -> Unit, onSnooze: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            event?.label?.takeIf { it.isNotBlank() } ?: "Event",
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        Text(
            event?.let { "%02d:%02d".format(it.hour, it.minute) } ?: "",
            style = MaterialTheme.typography.displayLarge,
            textAlign = TextAlign.Center
        )
        event?.anchorDate?.let { d ->
            Text(
                d.format(DateTimeFormatter.ofPattern("EEE, MMM d yyyy")),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center
            )
        }
        event?.let {
            Text(
                "${Zones.cityLabel(it.zoneId)}  ·  ${Zones.offsetLabel(it.zoneId)}",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(48.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            OutlinedButton(onClick = onSnooze) { Text("Snooze 9 min") }
            Button(onClick = onDismiss) { Text("Dismiss") }
        }
    }
}
