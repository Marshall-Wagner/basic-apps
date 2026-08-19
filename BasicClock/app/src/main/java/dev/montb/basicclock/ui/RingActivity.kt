package dev.montb.basicclock.ui

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
import dev.montb.basicclock.alarm.AlarmNotifier
import dev.montb.basicclock.alarm.AlarmScheduler
import dev.montb.basicclock.alarm.AlarmService
import dev.montb.basicclock.data.Alarm
import dev.montb.basicclock.data.AlarmStore
import dev.montb.basicclock.util.Zones

/**
 * The full-screen alarm UI shown over the lock screen. The sound and vibration are produced
 * by [AlarmService]; this screen only shows the alarm and its Dismiss / Snooze buttons, which
 * stop the service.
 */
class RingActivity : ComponentActivity() {

    private var alarm: Alarm? = null

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

        alarm = intent.getStringExtra(EXTRA_ID)?.let { AlarmStore.get(this, it) }
        val label = intent.getStringExtra(EXTRA_LABEL)

        setContent {
            BasicClockTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    RingScreen(
                        alarm = alarm,
                        label = label,
                        onDismiss = { stopAlarm(); finish() },
                        onSnooze = {
                            stopAlarm()
                            alarm?.let { AlarmScheduler.snooze(this, it) }
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
        const val EXTRA_ID = "alarm_id"
        const val EXTRA_LABEL = "label"
    }
}

@Composable
private fun RingScreen(alarm: Alarm?, label: String?, onDismiss: () -> Unit, onSnooze: () -> Unit) {
    val isTimer = alarm == null && label != null
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            label ?: alarm?.label?.takeIf { it.isNotBlank() } ?: "Alarm",
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        Text(
            if (isTimer) "Time's up" else alarm?.let { "%02d:%02d".format(it.hour, it.minute) } ?: "",
            style = if (isTimer) MaterialTheme.typography.displayMedium else MaterialTheme.typography.displayLarge,
            textAlign = TextAlign.Center
        )
        alarm?.let {
            Text(
                "${Zones.cityLabel(it.zoneId)}  ·  ${Zones.offsetLabel(it.zoneId)}",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(48.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (!isTimer) OutlinedButton(onClick = onSnooze) { Text("Snooze 9 min") }
            Button(onClick = onDismiss) { Text("Dismiss") }
        }
    }
}
