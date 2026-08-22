package dev.montb.basicclock.ui

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.format.DateFormat
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.montb.basicclock.alarm.AlarmScheduler
import dev.montb.basicclock.data.Alarm
import dev.montb.basicclock.data.AlarmStore
import dev.montb.basicclock.data.RecentZonesStore
import dev.montb.basicclock.data.WorldClockStore
import dev.montb.basicclock.util.Zones
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { BasicClockTheme { ClockApp() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ClockApp() {
    val context = LocalContext.current
    val alarms = remember { mutableStateListOf<Alarm>().apply { addAll(AlarmStore.getAll(context)) } }
    var editing by remember { mutableStateOf<Alarm?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var tab by remember { mutableIntStateOf(0) }  // 0 Alarms · 1 World · 2 Stopwatch · 3 Timer
    var addingCity by remember { mutableStateOf(false) }
    val worldZones = remember {
        mutableStateListOf<String>().apply { addAll(WorldClockStore.getAll(context)) }
    }
    // Stopwatch state is hoisted here so it keeps running when you switch tabs.
    val stopwatch = remember { StopwatchState() }

    fun reload() { alarms.clear(); alarms.addAll(AlarmStore.getAll(context)) }

    // Ask for notification permission (13+) so the full-screen alarm can post.
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        // Self-heal: re-arm every alarm on open, in case the ROM force-stopped us and Android
        // cancelled our pending alarms (otherwise they'd only return on reboot via BootReceiver).
        withContext(Dispatchers.IO) { AlarmScheduler.rescheduleAll(context) }
    }

    // Full-screen-intent access (Android 14+). Without it the alarm shows only as a heads-up
    // notification instead of taking over the screen. Re-checked on resume so the banner
    // clears once the user grants it.
    var fsiAllowed by remember { mutableStateOf(fullScreenIntentAllowed(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) {
                fsiAllowed = fullScreenIntentAllowed(context)
                // Re-sync the alarm rows with the store: a one-shot alarm that rang while we
                // were away flipped itself off in the background (AlarmReceiver), so its toggle
                // must now read off instead of staying stuck on.
                reload()
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = {
                Text(when (tab) { 0 -> "Alarms"; 1 -> "World Clock"; 2 -> "Stopwatch"; else -> "Timer" })
            })
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    icon = { Icon(Icons.Filled.Alarm, contentDescription = null) },
                    label = { Text("Alarms") }
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    icon = { Icon(Icons.Filled.Public, contentDescription = null) },
                    label = { Text("World") }
                )
                NavigationBarItem(
                    selected = tab == 2,
                    onClick = { tab = 2 },
                    icon = { Icon(Icons.Filled.Timer, contentDescription = null) },
                    label = { Text("Stopwatch") }
                )
                NavigationBarItem(
                    selected = tab == 3,
                    onClick = { tab = 3 },
                    icon = { Icon(Icons.Filled.HourglassEmpty, contentDescription = null) },
                    label = { Text("Timer") }
                )
            }
        },
        floatingActionButton = {
            if (tab == 0 || tab == 1) {
                FloatingActionButton(onClick = {
                    if (tab == 0) { editing = null; showEditor = true } else addingCity = true
                }) {
                    Icon(Icons.Filled.Add, contentDescription = if (tab == 0) "Add alarm" else "Add city")
                }
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                0 -> {
                    if (!fsiAllowed) FullScreenIntentBanner(onClick = { openFullScreenSettings(context) })
                    if (alarms.isEmpty()) {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("No alarms yet", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Tap + to add a time-zone alarm.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(alarms, key = { it.id }) { alarm ->
                                AlarmRow(
                                    alarm = alarm,
                                    onToggle = { on ->
                                        val updated = alarm.copy(enabled = on)
                                        AlarmStore.upsert(context, updated)
                                        if (on) AlarmScheduler.schedule(context, updated)
                                        else AlarmScheduler.cancel(context, updated)
                                        reload()
                                    },
                                    onClick = { editing = alarm; showEditor = true },
                                    onDelete = {
                                        AlarmScheduler.cancel(context, alarm)
                                        AlarmStore.delete(context, alarm.id)
                                        reload()
                                    }
                                )
                                HorizontalDivider()
                            }
                        }
                    }
                }
                1 -> WorldClockList(
                    zones = worldZones,
                    onRemove = { z ->
                        worldZones.clear()
                        worldZones.addAll(WorldClockStore.remove(context, z))
                    }
                )
                2 -> StopwatchScreen(stopwatch, Modifier.fillMaxSize())
                else -> TimerScreen(Modifier.fillMaxSize())
            }
        }
    }

    if (showEditor) {
        AlarmEditorDialog(
            initial = editing,
            onDismiss = { showEditor = false },
            onSave = { saved ->
                AlarmStore.upsert(context, saved)
                if (saved.enabled) AlarmScheduler.schedule(context, saved)
                else AlarmScheduler.cancel(context, saved)
                reload()
                showEditor = false
            }
        )
    }

    if (addingCity) {
        ZonePickerDialog(
            onPick = { z ->
                worldZones.clear()
                worldZones.addAll(WorldClockStore.add(context, z))
                addingCity = false
            },
            onDismiss = { addingCity = false }
        )
    }
}

@Composable
private fun AlarmRow(alarm: Alarm, onToggle: (Boolean) -> Unit, onClick: () -> Unit, onDelete: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("%02d:%02d".format(alarm.hour, alarm.minute), style = MaterialTheme.typography.headlineSmall)
            Text(
                "${Zones.label(alarm.zoneId)}  ·  ${Zones.offsetLabel(alarm.zoneId)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            val sub = buildString {
                append(daysSummary(alarm.days))
                if (alarm.label.isNotBlank()) append("  ·  ${alarm.label}")
            }
            if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "Delete") }
        Switch(checked = alarm.enabled, onCheckedChange = onToggle)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun AlarmEditorDialog(initial: Alarm?, onDismiss: () -> Unit, onSave: (Alarm) -> Unit) {
    val context = LocalContext.current
    val is24 = DateFormat.is24HourFormat(context)
    val timeState = rememberTimePickerState(
        initialHour = initial?.hour ?: 8,
        initialMinute = initial?.minute ?: 0,
        is24Hour = is24
    )
    var zoneId by remember { mutableStateOf(initial?.zoneId ?: Zones.device()) }
    val days = remember { mutableStateListOf<Int>().apply { initial?.days?.let { addAll(it) } } }
    var label by remember { mutableStateOf(initial?.label ?: "") }
    var pickingZone by remember { mutableStateOf(false) }
    var soundUri by remember { mutableStateOf(initial?.soundUri) }
    val ringtoneLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            @Suppress("DEPRECATION")
            val picked = result.data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            soundUri = picked?.toString()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "New alarm" else "Edit alarm") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                TimeInput(state = timeState)
                Spacer(Modifier.height(8.dp))
                Text("Time zone", style = MaterialTheme.typography.labelMedium)
                Surface(
                    onClick = { pickingZone = true },
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                ) {
                    Text(
                        "${Zones.label(zoneId)}  (${Zones.offsetLabel(zoneId)})",
                        modifier = Modifier.padding(12.dp)
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text("Sound", style = MaterialTheme.typography.labelMedium)
                Surface(
                    onClick = {
                        ringtoneLauncher.launch(
                            Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                                putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
                                putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Alarm sound")
                                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                                putExtra(
                                    RingtoneManager.EXTRA_RINGTONE_EXISTING_URI,
                                    soundUri?.let { Uri.parse(it) }
                                )
                            }
                        )
                    },
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                ) {
                    Text(ringtoneTitle(context, soundUri), modifier = Modifier.padding(12.dp))
                }
                Spacer(Modifier.height(8.dp))
                Text("Repeat", style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    val labels = listOf("M", "T", "W", "T", "F", "S", "S")
                    for (d in 1..7) {
                        FilterChip(
                            selected = d in days,
                            onClick = { if (d in days) days.remove(d) else days.add(d) },
                            label = { Text(labels[d - 1]) }
                        )
                    }
                }
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Label (optional)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions.Default,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                onSave(
                    (initial ?: Alarm(hour = 0, minute = 0, zoneId = zoneId)).copy(
                        hour = timeState.hour,
                        minute = timeState.minute,
                        zoneId = zoneId,
                        label = label.trim(),
                        days = days.toSet(),
                        enabled = true,
                        soundUri = soundUri
                    )
                )
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )

    if (pickingZone) {
        ZonePickerDialog(
            onPick = { zoneId = it; pickingZone = false },
            onDismiss = { pickingZone = false }
        )
    }
}

@Composable
private fun ZonePickerDialog(onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    val recents = remember { RecentZonesStore.get(context) }
    val matches = remember(query) { Zones.all.filter { Zones.matches(it, query) } }

    fun pick(z: String) { RecentZonesStore.record(context, z); onPick(z) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pick a time zone") },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search city or country") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 340.dp)) {
                    if (query.isBlank() && recents.isNotEmpty()) {
                        item { ZonePickerHeader("Recent") }
                        items(recents, key = { "recent_$it" }) { z -> ZonePickerRow(z) { pick(z) } }
                        item { ZonePickerHeader("All cities") }
                    }
                    items(matches, key = { it }) { z -> ZonePickerRow(z) { pick(z) } }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@Composable
private fun ZonePickerHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp)
    )
}

@Composable
private fun ZonePickerRow(zoneId: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp)
    ) {
        Text(Zones.label(zoneId), style = MaterialTheme.typography.bodyLarge)
        Text(
            Zones.offsetLabel(zoneId),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    HorizontalDivider()
}

private fun daysSummary(days: Set<Int>): String {
    if (days.isEmpty()) return "Once"
    if (days == setOf(1, 2, 3, 4, 5, 6, 7)) return "Every day"
    if (days == setOf(1, 2, 3, 4, 5)) return "Weekdays"
    if (days == setOf(6, 7)) return "Weekends"
    val labels = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    return days.sorted().joinToString(", ") { labels[it - 1] }
}

private fun ringtoneTitle(context: Context, uri: String?): String {
    val u = uri?.let { Uri.parse(it) }
        ?: RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_ALARM)
    return runCatching { RingtoneManager.getRingtone(context, u)?.getTitle(context) }.getOrNull()
        ?: "Default alarm"
}

private fun fullScreenIntentAllowed(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true
    val nm = context.getSystemService(NotificationManager::class.java)
    return nm?.canUseFullScreenIntent() ?: true
}

private fun openFullScreenSettings(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${context.packageName}"))
            )
        }
    }
}

@Composable
private fun FullScreenIntentBanner(onClick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth().padding(12.dp).clickable(onClick = onClick)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Full-screen alarms are off",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Text(
                "Alarms will only show as a notification instead of taking over the screen. Tap to enable.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}

@Composable
private fun WorldClockList(zones: List<String>, onRemove: (String) -> Unit) {
    if (zones.isEmpty()) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("No cities yet", style = MaterialTheme.typography.titleMedium)
            Text(
                "Tap + to add a city's clock.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }
    val is24 = DateFormat.is24HourFormat(LocalContext.current)
    // Tick once a second so the clocks stay live.
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) {
        while (true) { now = Instant.now(); delay(1000) }
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(zones, key = { it }) { z ->
            WorldClockRow(zoneId = z, now = now, is24 = is24, onRemove = { onRemove(z) })
            HorizontalDivider()
        }
    }
}

@Composable
private fun WorldClockRow(zoneId: String, now: Instant, is24: Boolean, onRemove: () -> Unit) {
    val zoned = remember(zoneId, now) { runCatching { now.atZone(ZoneId.of(zoneId)) }.getOrNull() }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(Zones.label(zoneId), style = MaterialTheme.typography.titleMedium)
            Text(
                zoned?.let {
                    it.format(DateTimeFormatter.ofPattern("EEE, MMM d")) + "  ·  " + Zones.offsetLabel(zoneId, now)
                } ?: zoneId,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            zoned?.format(DateTimeFormatter.ofPattern(if (is24) "HH:mm" else "h:mm a")) ?: "",
            style = MaterialTheme.typography.headlineSmall
        )
        IconButton(onClick = onRemove) { Icon(Icons.Filled.Delete, contentDescription = "Remove") }
    }
}
