package dev.montb.basicclock.ui

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.montb.basicclock.alarm.TimerScheduler
import dev.montb.basicclock.data.TimerStore
import kotlinx.coroutines.delay

/* ------------------------------- Stopwatch ------------------------------- */

/**
 * Stopwatch state, hoisted above the tab switch so it keeps running when you move to another
 * tab. Elapsed time is derived from [SystemClock.elapsedRealtime] marks, not incremented by a
 * ticker, so it stays exact no matter when the UI happens to recompose.
 */
class StopwatchState {
    var running by mutableStateOf(false)
        private set
    private var base by mutableLongStateOf(0L)       // ms banked before the current run
    private var startMark by mutableLongStateOf(0L)  // elapsedRealtime() when the current run began
    val laps = mutableStateListOf<Long>()            // newest first

    fun elapsed(now: Long): Long = if (running) base + (now - startMark) else base

    fun toggle() {
        val now = SystemClock.elapsedRealtime()
        if (running) { base += now - startMark; running = false }
        else { startMark = now; running = true }
    }

    fun reset() { running = false; base = 0L; startMark = 0L; laps.clear() }

    fun lap() { laps.add(0, elapsed(SystemClock.elapsedRealtime())) }
}

@Composable
fun StopwatchScreen(state: StopwatchState, modifier: Modifier = Modifier) {
    // Tick only to refresh the display while running; the value itself is timestamp-derived.
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(state.running) {
        while (state.running) { now = SystemClock.elapsedRealtime(); delay(31) }
        now = SystemClock.elapsedRealtime()
    }
    val elapsed = state.elapsed(now)

    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(24.dp))
        Text(
            text = formatStopwatch(elapsed),
            style = MaterialTheme.typography.displayMedium.copy(fontFamily = FontFamily.Monospace)
        )
        Spacer(Modifier.height(32.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = { if (state.running) state.lap() else state.reset() },
                enabled = state.running || elapsed > 0L
            ) { Text(if (state.running) "Lap" else "Reset") }
            Button(onClick = { state.toggle() }) {
                Text(if (state.running) "Pause" else "Start")
            }
        }
        if (state.laps.isNotEmpty()) {
            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                itemsIndexed(state.laps) { index, lapTime ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Lap ${state.laps.size - index}", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            formatStopwatch(lapTime),
                            style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace)
                        )
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

/* --------------------------------- Timer --------------------------------- */

@Composable
fun TimerScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // `nowMs` ticks the countdown; `refresh` is bumped on each action so a Start / Pause /
    // Resume / Cancel is reflected instantly instead of waiting for the next 200 ms tick, the
    // store isn't Compose state, so without this nudge the first start feels laggy.
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) { nowMs = System.currentTimeMillis(); delay(200) }
    }
    val state = remember(nowMs, refresh) { TimerStore.read(context) }
    val active = state.running || state.pausedRemaining > 0L

    Column(
        modifier = modifier.padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (!active) {
            TimerInput(
                initialMs = state.lastDuration,
                onStart = { ms -> if (ms > 0L) { TimerScheduler.start(context, ms); refresh++ } }
            )
        } else {
            val remaining =
                if (state.running) (state.endAt - nowMs).coerceAtLeast(0L) else state.pausedRemaining
            Text(
                text = formatTimer(remaining),
                style = MaterialTheme.typography.displayLarge.copy(fontFamily = FontFamily.Monospace)
            )
            Spacer(Modifier.height(32.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { TimerScheduler.cancel(context); refresh++ }) { Text("Cancel") }
                if (state.running) {
                    Button(onClick = { TimerScheduler.pause(context); refresh++ }) { Text("Pause") }
                } else {
                    Button(onClick = { TimerScheduler.resume(context); refresh++ }) { Text("Resume") }
                }
            }
        }
    }
}

@Composable
private fun TimerInput(initialMs: Long, onStart: (Long) -> Unit) {
    var h by rememberSaveable { mutableStateOf((initialMs / 3_600_000L).toString()) }
    var m by rememberSaveable { mutableStateOf(((initialMs / 60_000L) % 60).toString()) }
    var s by rememberSaveable { mutableStateOf(((initialMs / 1_000L) % 60).toString()) }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TimeField("Hrs", h) { h = it }
            TimeField("Min", m) { m = it }
            TimeField("Sec", s) { s = it }
        }
        Spacer(Modifier.height(28.dp))
        val ms = (h.toIntOrNull() ?: 0) * 3_600_000L +
            (m.toIntOrNull() ?: 0) * 60_000L +
            (s.toIntOrNull() ?: 0) * 1_000L
        Button(onClick = { onStart(ms) }, enabled = ms > 0L) { Text("Start") }
    }
}

@Composable
private fun TimeField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { new -> onChange(new.filter { it.isDigit() }.take(2)) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.width(88.dp)
    )
}

/* ------------------------------ formatting ------------------------------- */

/** Elapsed → "M:SS.cc" (or "H:MM:SS.cc" past an hour), centiseconds, for the stopwatch. */
private fun formatStopwatch(ms: Long): String {
    val cs = (ms / 10) % 100
    val s = (ms / 1_000) % 60
    val m = (ms / 60_000) % 60
    val h = ms / 3_600_000
    return if (h > 0) "%d:%02d:%02d.%02d".format(h, m, s, cs)
    else "%d:%02d.%02d".format(m, s, cs)
}

/** Remaining → "M:SS" (or "H:MM:SS"), rounded up to whole seconds, for the countdown. */
private fun formatTimer(ms: Long): String {
    val total = (ms + 999) / 1_000
    val s = total % 60
    val m = (total / 60) % 60
    val h = total / 3_600
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
