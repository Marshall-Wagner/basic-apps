package dev.montb.basiccalendar.ui

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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.montb.basiccalendar.alarm.EventScheduler
import dev.montb.basiccalendar.data.CalendarEvent
import dev.montb.basiccalendar.data.EventStore
import dev.montb.basiccalendar.data.RecentZonesStore
import dev.montb.basiccalendar.data.Repeat
import dev.montb.basiccalendar.util.Zones
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { BasicCalendarTheme { CalendarApp() } }
    }
}

/* ------------------------------ occurrence logic ------------------------------ */

/** Does [event] have an occurrence exactly on [date]? Mirrors CalendarEvent.nextTrigger's
 *  recurrence rules (repeats never slide onto a date the day-of-month/month doesn't allow). */
private fun occursOn(event: CalendarEvent, date: LocalDate): Boolean {
    val anchor = event.anchorDate ?: return false
    return when (event.repeat) {
        Repeat.NONE -> date == anchor
        Repeat.WEEKLY -> !date.isBefore(anchor) && date.dayOfWeek == anchor.dayOfWeek
        Repeat.MONTHLY -> !date.isBefore(anchor) && date.dayOfMonth == event.day
        Repeat.YEARLY -> !date.isBefore(anchor) &&
            date.monthValue == event.month && date.dayOfMonth == event.day
    }
}

/* ---------------------------------- app shell --------------------------------- */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CalendarApp() {
    val context = LocalContext.current
    val events = remember { mutableStateListOf<CalendarEvent>().apply { addAll(EventStore.getAll(context)) } }
    var month by remember { mutableStateOf(YearMonth.now()) }
    var selected by remember { mutableStateOf<LocalDate?>(null) }
    var editing by remember { mutableStateOf<CalendarEvent?>(null) }
    var showEditor by remember { mutableStateOf(false) }

    fun reload() { events.clear(); events.addAll(EventStore.getAll(context)) }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        // Self-heal: re-arm every event on open, in case the ROM force-stopped us and Android
        // dropped our pending alarms (they'd otherwise only return on reboot via BootReceiver).
        withContext(Dispatchers.IO) { EventScheduler.rescheduleAll(context) }
    }

    // Full-screen-intent access (Android 14+); reload the event list on resume so an event
    // that fired and switched itself off shows its toggle off when you come back.
    var fsiAllowed by remember { mutableStateOf(fullScreenIntentAllowed(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) {
                fsiAllowed = fullScreenIntentAllowed(context)
                reload()
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Calendar") }) },
        floatingActionButton = {
            FloatingActionButton(onClick = { editing = null; showEditor = true }) {
                Icon(Icons.Filled.Add, contentDescription = "Add event")
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (!fsiAllowed) FullScreenIntentBanner(onClick = { openFullScreenSettings(context) })

            MonthHeader(
                month = month,
                onPrev = { month = month.minusMonths(1) },
                onNext = { month = month.plusMonths(1) },
                onToday = { month = YearMonth.now(); selected = LocalDate.now() }
            )
            MonthGrid(
                month = month,
                selected = selected,
                hasEvent = { date -> events.any { it.enabled && occursOn(it, date) } },
                onPick = { date -> selected = if (selected == date) null else date }
            )
            HorizontalDivider()
            EventList(
                modifier = Modifier.weight(1f),
                events = events,
                selected = selected,
                onToggle = { ev, on ->
                    val updated = ev.copy(enabled = on)
                    EventStore.upsert(context, updated)
                    if (on) EventScheduler.schedule(context, updated) else EventScheduler.cancel(context, updated)
                    reload()
                },
                onClick = { ev -> editing = ev; showEditor = true },
                onDelete = { ev ->
                    EventScheduler.cancel(context, ev)
                    EventStore.delete(context, ev.id)
                    reload()
                }
            )
        }
    }

    if (showEditor) {
        EventEditorDialog(
            initial = editing,
            defaultDate = selected ?: LocalDate.now(),
            onDismiss = { showEditor = false },
            onSave = { saved ->
                EventStore.upsert(context, saved)
                if (saved.enabled) EventScheduler.schedule(context, saved) else EventScheduler.cancel(context, saved)
                reload()
                showEditor = false
            }
        )
    }
}

/* --------------------------------- month grid --------------------------------- */

@Composable
private fun MonthHeader(month: YearMonth, onPrev: () -> Unit, onNext: () -> Unit, onToday: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onPrev) { Icon(Icons.Filled.ChevronLeft, contentDescription = "Previous month") }
        Text(
            month.format(DateTimeFormatter.ofPattern("MMMM yyyy")),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = onToday) { Icon(Icons.Filled.Today, contentDescription = "Jump to today") }
        IconButton(onClick = onNext) { Icon(Icons.Filled.ChevronRight, contentDescription = "Next month") }
    }
}

@Composable
private fun MonthGrid(
    month: YearMonth,
    selected: LocalDate?,
    hasEvent: (LocalDate) -> Boolean,
    onPick: (LocalDate) -> Unit
) {
    val firstDayOfWeek = WeekFields.of(Locale.getDefault()).firstDayOfWeek
    // Weekday header labels starting at the locale's first day.
    val weekdays = (0..6).map { firstDayOfWeek.plus(it.toLong()) }
    val today = LocalDate.now()

    val lead = ((month.atDay(1).dayOfWeek.value - firstDayOfWeek.value) + 7) % 7
    val daysInMonth = month.lengthOfMonth()
    val totalCells = ((lead + daysInMonth + 6) / 7) * 7

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        Row(Modifier.fillMaxWidth()) {
            weekdays.forEach { dow ->
                Text(
                    dow.getDisplayName(TextStyle.SHORT, Locale.getDefault()).take(3),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f).padding(vertical = 4.dp)
                )
            }
        }
        var cell = 0
        while (cell < totalCells) {
            Row(Modifier.fillMaxWidth()) {
                for (col in 0 until 7) {
                    val dayNum = cell - lead + 1
                    val date = if (dayNum in 1..daysInMonth) month.atDay(dayNum) else null
                    DayCell(
                        date = date,
                        isToday = date == today,
                        isSelected = date != null && date == selected,
                        hasEvent = date != null && hasEvent(date),
                        onClick = { date?.let(onPick) },
                        modifier = Modifier.weight(1f)
                    )
                    cell++
                }
            }
        }
    }
}

@Composable
private fun DayCell(
    date: LocalDate?,
    isToday: Boolean,
    isSelected: Boolean,
    hasEvent: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .height(44.dp)
            .padding(2.dp)
            .then(
                if (isSelected) Modifier.background(MaterialTheme.colorScheme.primaryContainer, CircleShape)
                else if (isToday) Modifier.border(1.dp, MaterialTheme.colorScheme.primary, CircleShape)
                else Modifier
            )
            .then(if (date != null) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        if (date != null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    date.dayOfMonth.toString(),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isToday || isSelected) FontWeight.Bold else FontWeight.Normal,
                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurface
                )
                Box(
                    Modifier.size(5.dp).background(
                        if (hasEvent) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent,
                        CircleShape
                    )
                )
            }
        }
    }
}

/* ---------------------------------- event list -------------------------------- */

@Composable
private fun EventList(
    modifier: Modifier = Modifier,
    events: List<CalendarEvent>,
    selected: LocalDate?,
    onToggle: (CalendarEvent, Boolean) -> Unit,
    onClick: (CalendarEvent) -> Unit,
    onDelete: (CalendarEvent) -> Unit
) {
    val shown: List<CalendarEvent> = if (selected != null) {
        events.filter { occursOn(it, selected) }
    } else {
        events.sortedWith(compareBy(nullsLast<Long>()) { it.nextTrigger()?.toEpochMilli() })
    }
    val header = if (selected != null)
        "Events on ${selected.format(DateTimeFormatter.ofPattern("EEE, MMM d"))}"
    else "Upcoming"

    Column(modifier.fillMaxWidth()) {
        Text(
            header,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp)
        )
        if (shown.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text(
                    if (selected != null) "No events on this day.\nTap + to add one."
                    else "No events yet.\nTap + to add a date-and-time-zone event.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                items(shown, key = { it.id }) { ev ->
                    EventRow(ev, onToggle = { onToggle(ev, it) }, onClick = { onClick(ev) }, onDelete = { onDelete(ev) })
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun EventRow(
    event: CalendarEvent,
    onToggle: (Boolean) -> Unit,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                event.anchorDate?.format(DateTimeFormatter.ofPattern("EEE, MMM d yyyy")) ?: "Invalid date",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                "%02d:%02d  ·  %s  ·  %s".format(
                    event.hour, event.minute, Zones.label(event.zoneId), Zones.offsetLabel(event.zoneId)
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            val sub = buildString {
                append(repeatSummary(event.repeat))
                if (event.label.isNotBlank()) append("  ·  ${event.label}")
            }
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "Delete") }
        Switch(checked = event.enabled, onCheckedChange = onToggle)
    }
}

private fun repeatSummary(repeat: Repeat): String = when (repeat) {
    Repeat.NONE -> "Once"
    Repeat.WEEKLY -> "Weekly"
    Repeat.MONTHLY -> "Monthly"
    Repeat.YEARLY -> "Yearly"
}

/* ----------------------------------- editor ----------------------------------- */

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun EventEditorDialog(
    initial: CalendarEvent?,
    defaultDate: LocalDate,
    onDismiss: () -> Unit,
    onSave: (CalendarEvent) -> Unit
) {
    val context = LocalContext.current
    val is24 = DateFormat.is24HourFormat(context)
    val startDate = initial?.anchorDate ?: defaultDate
    val timeState = rememberTimePickerState(
        initialHour = initial?.hour ?: 8,
        initialMinute = initial?.minute ?: 0,
        is24Hour = is24
    )
    var date by remember { mutableStateOf(startDate) }
    var zoneId by remember { mutableStateOf(initial?.zoneId ?: Zones.device()) }
    var repeat by remember { mutableStateOf(initial?.repeat ?: Repeat.NONE) }
    var label by remember { mutableStateOf(initial?.label ?: "") }
    var soundUri by remember { mutableStateOf(initial?.soundUri) }
    var pickingZone by remember { mutableStateOf(false) }
    var pickingDate by remember { mutableStateOf(false) }

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
        title = { Text(if (initial == null) "New event" else "Edit event") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text("Date", style = MaterialTheme.typography.labelMedium)
                PickerRow(date.format(DateTimeFormatter.ofPattern("EEE, MMM d yyyy"))) { pickingDate = true }

                Spacer(Modifier.height(8.dp))
                Text("Time", style = MaterialTheme.typography.labelMedium)
                TimeInput(state = timeState)

                Text("Time zone", style = MaterialTheme.typography.labelMedium)
                PickerRow("${Zones.label(zoneId)}  (${Zones.offsetLabel(zoneId)})") { pickingZone = true }

                Spacer(Modifier.height(8.dp))
                Text("Repeat", style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Repeat.entries.forEach { r ->
                        FilterChip(
                            selected = repeat == r,
                            onClick = { repeat = r },
                            label = { Text(repeatSummary(r)) }
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))
                Text("Sound", style = MaterialTheme.typography.labelMedium)
                PickerRow(ringtoneTitle(context, soundUri)) {
                    ringtoneLauncher.launch(
                        Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
                            putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Event sound")
                            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                            putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, soundUri?.let { Uri.parse(it) })
                        }
                    )
                }

                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Label (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                onSave(
                    (initial ?: CalendarEvent(year = 0, month = 1, day = 1, hour = 0, minute = 0, zoneId = zoneId))
                        .copy(
                            year = date.year,
                            month = date.monthValue,
                            day = date.dayOfMonth,
                            hour = timeState.hour,
                            minute = timeState.minute,
                            zoneId = zoneId,
                            label = label.trim(),
                            repeat = repeat,
                            enabled = true,
                            soundUri = soundUri
                        )
                )
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )

    if (pickingDate) {
        val dpState = rememberDatePickerState(
            initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        )
        DatePickerDialog(
            onDismissRequest = { pickingDate = false },
            confirmButton = {
                TextButton(onClick = {
                    dpState.selectedDateMillis?.let { millis ->
                        date = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                    }
                    pickingDate = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickingDate = false }) { Text("Cancel") } }
        ) { DatePicker(state = dpState) }
    }

    if (pickingZone) {
        ZonePickerDialog(
            onPick = { zoneId = it; pickingZone = false },
            onDismiss = { pickingZone = false }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PickerRow(text: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
    ) {
        Text(text, modifier = Modifier.padding(12.dp))
    }
}

/* -------------------------------- zone picker --------------------------------- */

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

/* --------------------------------- helpers ------------------------------------ */

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
                "Events will only show as a notification instead of taking over the screen. Tap to enable.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}
