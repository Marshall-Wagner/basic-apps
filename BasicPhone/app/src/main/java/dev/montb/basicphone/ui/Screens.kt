package dev.montb.basicphone.ui

import android.content.Intent
import android.provider.CallLog
import android.provider.ContactsContract
import android.text.format.DateFormat
import android.widget.Toast
import android.text.format.DateUtils
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallMissed
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Voicemail
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import android.telecom.Call
import dev.montb.basicphone.data.CallLogEntry
import dev.montb.basicphone.incall.CallManager
import dev.montb.basicphone.incall.InCallActivity
import dev.montb.basicphone.util.Clip
import dev.montb.basicphone.util.Contacts
import dev.montb.basicphone.util.MissedCalls
import dev.montb.basicphone.util.Prefs
import dev.montb.basicphone.util.Blocking
import dev.montb.basicphone.util.NumberLookup
import dev.montb.basicphone.util.Sims
import dev.montb.basicphone.util.Voicemail
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    vm: DialerViewModel,
    volteAvailable: Boolean?,
    isDefault: Boolean,
    onOpenKeypad: () -> Unit,
    onMakeDefault: () -> Unit,
    onVoicemail: () -> Unit,
    onVoicemailSetup: () -> Unit,
    onCallNumber: (number: String, name: String?) -> Unit
) {
    val context = LocalContext.current
    val sims = remember { Sims.active(context) }
    val accountToCarrier = remember(sims) { sims.associate { it.subId.toString() to it.carrier } }
    // Known voicemail numbers so the log shows "Voicemail" instead of the raw number.
    val voicemailNumbers = remember { Voicemail.knownNumbers(context) }
    val calls = vm.callLog.collectAsLazyPagingItems()

    // Treat missed calls as "seen" whenever the call log is in the foreground: clear Telecom's
    // missed-call count on resume AND whenever a new entry is logged. The latter covers a call
    // missed while the dialer is already open (which fires no ON_RESUME), so the count never
    // lingers. Gated to RESUMED so we never dismiss a missed-call notification while the app is
    // backgrounded, only while the user is actually looking at the log.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            snapshotFlow { calls.itemCount }
                .distinctUntilChanged()
                .collect { MissedCalls.clear(context) }
        }
    }
    // The call log needs READ_CALL_LOG, which is denied on a fresh install until the user grants
    // it (or the app is made the default dialer, which grants it). Re-check on each resume and
    // refresh the list the moment it becomes available, so it fills in without a manual reopen.
    var callLogGranted by remember { mutableStateOf(hasCallLogPermission(context)) }
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val granted = hasCallLogPermission(context)
            if (granted && !callLogGranted) calls.refresh()
            callLogGranted = granted
        }
    }
    var menuOpen by remember { mutableStateOf(false) }
    var screeningDialogOpen by remember { mutableStateOf(false) }
    var searchEngineDialogOpen by remember { mutableStateOf(false) }
    var blockedDialogOpen by remember { mutableStateOf(false) }
    var blockTarget by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Phone") },
                actions = {
                    VolteChip(volteAvailable)
                    IconButton(onClick = onVoicemail) {
                        Icon(Icons.Filled.Voicemail, contentDescription = "Call voicemail")
                    }
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "More")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Voicemail number…") },
                            onClick = { menuOpen = false; onVoicemailSetup() }
                        )
                        DropdownMenuItem(
                            text = { Text("Spam call screening…") },
                            onClick = { menuOpen = false; screeningDialogOpen = true }
                        )
                        DropdownMenuItem(
                            text = { Text("Number lookup search…") },
                            onClick = { menuOpen = false; searchEngineDialogOpen = true }
                        )
                        DropdownMenuItem(
                            text = { Text("Blocked numbers…") },
                            onClick = { menuOpen = false; blockedDialogOpen = true }
                        )
                        if (!isDefault) {
                            DropdownMenuItem(
                                text = { Text("Set as default phone app") },
                                onClick = { menuOpen = false; onMakeDefault() }
                            )
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onOpenKeypad) {
                Icon(Icons.Filled.Dialpad, contentDescription = "Dialpad")
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // "Return to call" banner, lets you reopen the in-call screen after it
            // was backgrounded (e.g. screen timed out in the car, or you switched apps).
            OngoingCallBanner()
            if (screeningDialogOpen) SpamScreeningDialog(onDismiss = { screeningDialogOpen = false })
            if (searchEngineDialogOpen) SearchEngineDialog(onDismiss = { searchEngineDialogOpen = false })
            if (blockedDialogOpen) BlockedNumbersDialog(onDismiss = { blockedDialogOpen = false })
            blockTarget?.let { number ->
                BlockConfirmDialog(
                    number = number,
                    onConfirm = {
                        val ok = Blocking.block(context, number)
                        Toast.makeText(
                            context,
                            if (ok) "Blocked $number" else "Couldn't block this number",
                            Toast.LENGTH_SHORT
                        ).show()
                        blockTarget = null
                    },
                    onDismiss = { blockTarget = null }
                )
            }

            val refresh = calls.loadState.refresh
            when {
                refresh is LoadState.Error -> ErrorState(
                    modifier = Modifier.fillMaxSize(),
                    onRetry = { calls.retry() }
                )
                refresh is LoadState.NotLoading && calls.itemCount == 0 -> EmptyState(
                    modifier = Modifier.fillMaxSize()
                )
                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(
                        count = calls.itemCount,
                        key = calls.itemKey { it.id }
                    ) { index ->
                        calls[index]?.let { entry ->
                            val carrier = if (sims.size > 1) accountToCarrier[entry.accountId] else null
                            // Resolve the display name: contact name, "Voicemail", or number.
                            val display = when {
                                !entry.name.isNullOrBlank() -> entry.name
                                Voicemail.isVoicemail(entry.number, voicemailNumbers) -> "Voicemail"
                                else -> entry.number.ifBlank { "Unknown" }
                            }
                            val ctx = LocalContext.current
                            CallRow(
                                entry, display, carrier,
                                onClick = { onCallNumber(entry.number, display) },
                                onLookup = { NumberLookup.lookup(ctx, entry.number) },
                                onCopy = { Clip.copy(ctx, "Phone number", entry.number) },
                                onAddContact = {
                                    runCatching {
                                        ctx.startActivity(
                                            Intent(Intent.ACTION_INSERT_OR_EDIT).apply {
                                                type = ContactsContract.Contacts.CONTENT_ITEM_TYPE
                                                putExtra(ContactsContract.Intents.Insert.PHONE, entry.number)
                                            }
                                        )
                                    }
                                },
                                onBlock = { blockTarget = entry.number }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OngoingCallBanner() {
    val context = LocalContext.current
    val call by CallManager.state.collectAsState()
    // Show whenever there's a live call that isn't already finished.
    val ongoing = call.active && call.callState != Call.STATE_DISCONNECTED
    if (!ongoing) return

    Surface(
        color = Color(0xFF1B7F4B),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { InCallActivity.start(context) }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.Call, contentDescription = null, tint = Color.White,
                modifier = Modifier.size(20.dp))
            Column(modifier = Modifier.weight(1f).padding(start = 16.dp)) {
                Text(
                    "Tap to return to call",
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    call.displayName,
                    color = Color.White.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CallRow(
    entry: CallLogEntry,
    displayName: String,
    carrierLabel: String?,
    onClick: () -> Unit,
    onLookup: () -> Unit,
    onCopy: () -> Unit,
    onAddContact: () -> Unit,
    onBlock: () -> Unit
) {
    val (icon, tint) = typeIcon(entry.type)
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = { menuOpen = true })
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        Column(modifier = Modifier.weight(1f).padding(start = 16.dp)) {
            Text(
                displayName,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val context = LocalContext.current
            val rel = formatCallTime(context, entry.date)
            val dur = if (entry.durationSec > 0) "  •  ${formatDuration(entry.durationSec)}" else ""
            val car = if (carrierLabel != null) "  •  $carrierLabel" else ""
            Text(
                rel + dur + car,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        // Offline spam signal (hidden caller / repeated unknown). Tap-and-hold any row
        // for the "Look up online" action.
        entry.spamHint.label?.let { hint ->
            Text(
                hint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            if (entry.number.isNotBlank()) {
                DropdownMenuItem(
                    text = { Text("Add contact") },
                    onClick = { menuOpen = false; onAddContact() }
                )
                DropdownMenuItem(
                    text = { Text("Copy number") },
                    onClick = { menuOpen = false; onCopy() }
                )
            }
            DropdownMenuItem(
                text = { Text("Look up number online") },
                onClick = { menuOpen = false; onLookup() }
            )
            if (entry.number.isNotBlank()) {
                DropdownMenuItem(
                    text = { Text("Block number") },
                    onClick = { menuOpen = false; onBlock() }
                )
            }
        }
    }
}

@Composable
private fun VolteChip(available: Boolean?) {
    val label = when (available) {
        true -> "VoLTE"
        false -> "No VoLTE"
        null -> "VoLTE ?"
    }
    AssistChip(onClick = { }, label = { Text(label, fontSize = 12.sp) })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeypadScreen(onDismiss: () -> Unit, onCall: (String, Int) -> Unit) {
    val context = LocalContext.current
    val sims = remember { Sims.active(context) }
    var input by remember { mutableStateOf("") }
    var selectedSub by remember(sims) { mutableStateOf(sims.firstOrNull()?.subId ?: -1) }
    var results by remember { mutableStateOf(emptyList<Contacts.Match>()) }
    val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "*", "0", "#")

    // Search contacts (by name OR number) whenever the input changes, off the main thread.
    LaunchedEffect(input) {
        val q = input.trim()
        results = if (q.isEmpty()) emptyList()
        else withContext(Dispatchers.IO) { Contacts.search(context, q) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Dial") },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Editable field: type a contact name OR a number. The dial pad below also
            // appends digits here.
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("Name or number") }
            )

            // SIM picker, only on a dual-SIM device.
            if (sims.size > 1) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    sims.forEach { sim ->
                        FilterChip(
                            selected = selectedSub == sim.subId,
                            onClick = { selectedSub = sim.subId },
                            label = { Text("SIM ${sim.slotIndex + 1} · ${sim.carrier}") }
                        )
                    }
                }
            }

            // Two modes, chosen by what you're typing:
            //  • Letters → you're name-searching with the soft keyboard. Show the matches
            //    as a full tap-to-call list and drop the keypad, it's useless for letters
            //    and would otherwise cover the list when the keyboard pushes it up.
            //  • Digits / empty → keep the fixed dial pad anchored below, with any matches
            //    in the reserved region above it, so the keypad never shifts while dialing.
            val nameSearch = input.any { it.isLetter() }
            if (nameSearch) {
                ContactMatches(
                    results, selectedSub, onCall,
                    Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp)
                )
            } else {
                Box(modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp)) {
                    if (results.isNotEmpty()) {
                        ContactMatches(results, selectedSub, onCall, Modifier.fillMaxSize())
                    }
                }

                // Fixed-position dial pad: a 4×3 grid of fixed-height keys, anchored just
                // above the action row. Its height never changes, so the keys stay put no
                // matter what's shown above them.
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    keys.chunked(3).forEach { row ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            row.forEach { key ->
                                Surface(
                                    shape = MaterialTheme.shapes.large,
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(64.dp)
                                        .clickable { input += key }
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(key, style = MaterialTheme.typography.headlineSmall)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                IconButton(onClick = { if (input.isNotEmpty()) input = input.dropLast(1) }) {
                    Icon(Icons.AutoMirrored.Filled.Backspace, contentDescription = "Delete")
                }
                FloatingActionButton(onClick = { if (input.isNotBlank()) onCall(input, selectedSub) }) {
                    Icon(Icons.Filled.Call, contentDescription = "Call")
                }
                Box(modifier = Modifier.size(48.dp)) // spacer to balance the row
            }
        }
    }
}

/** Scrollable list of contact matches; each row dials on tap. Fills the reserved
 *  region above the fixed dial pad, so the keypad never shifts as matches appear. */
@Composable
private fun ContactMatches(
    results: List<Contacts.Match>,
    selectedSub: Int,
    onCall: (String, Int) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(modifier = modifier) {
        items(count = results.size, key = { results[it].number }) { index ->
            val match = results[index]
            ContactResultRow(match) { onCall(match.number, selectedSub) }
        }
    }
}

@Composable
private fun ContactResultRow(match: Contacts.Match, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.Call, contentDescription = null, modifier = Modifier.size(20.dp))
        Column(modifier = Modifier.weight(1f).padding(start = 16.dp)) {
            Text(match.name, style = MaterialTheme.typography.titleMedium)
            Text(
                match.number,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** A number (and optional resolved name) the user chose to call from the log. */
data class CallTarget(val number: String, val name: String?)

@Composable
fun ConfirmCallDialog(
    target: CallTarget,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val label = target.name?.takeIf { it.isNotBlank() } ?: target.number
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Call, contentDescription = null) },
        title = { Text("Call $label?") },
        text = {
            // Show the number too when we have a name, so you know what you're dialing.
            if (target.name?.isNotBlank() == true) Text(target.number)
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Call") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VoicemailSimPickerDialog(
    sims: List<dev.montb.basicphone.util.SimInfo>,
    context: android.content.Context,
    onDismiss: () -> Unit,
    onPick: (subId: Int) -> Unit,
    onSetup: (subId: Int) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Which voicemail?") },
        text = {
            Column {
                Text(
                    "Pick the SIM whose voicemail box you want. Long-press to set up its number/PIN.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                sims.forEach { sim ->
                    val configured = Prefs.savedVoicemailNumber(context, sim.subId) != null
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .combinedClickable(
                                onClick = { onPick(sim.subId) },
                                onLongClick = { onSetup(sim.subId) }
                            )
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("SIM ${sim.slotIndex + 1} · ${sim.carrier}",
                                style = MaterialTheme.typography.titleSmall)
                            Text(
                                if (configured) "Tap to call · long-press to edit"
                                else "Not set up — tap to configure",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun VoicemailSetupDialog(
    simLabel: String?,
    subId: Int,
    carrierName: String?,
    initialNumber: String,
    initialPin: String,
    onDismiss: () -> Unit,
    onSave: (number: String, pin: String) -> Unit
) {
    val context = LocalContext.current
    var number by remember { mutableStateOf(initialNumber) }
    var pin by remember { mutableStateOf(initialPin) }
    // Auto-detect method: shortcut (*86, MVNO-safe) vs. a full carrier access number.
    var method by remember { mutableStateOf(dev.montb.basicphone.util.VoicemailNumbers.Method.SHORTCUT) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (simLabel != null) "Voicemail · $simLabel" else "Voicemail") },
        text = {
            Column {
                Text(
                    "Enter your carrier's voicemail access number, or auto-detect it. " +
                        "The Voicemail button dials this directly, bypassing the phone's " +
                        "broken voicemail setting.",
                    style = MaterialTheme.typography.bodySmall
                )
                // Auto-detect: method toggle + fill button. Always editable after.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                ) {
                    FilterChip(
                        selected = method == dev.montb.basicphone.util.VoicemailNumbers.Method.SHORTCUT,
                        onClick = { method = dev.montb.basicphone.util.VoicemailNumbers.Method.SHORTCUT },
                        label = { Text("Shortcut (*86)") }
                    )
                    Spacer(Modifier.size(6.dp))
                    FilterChip(
                        selected = method == dev.montb.basicphone.util.VoicemailNumbers.Method.FULL_NUMBER,
                        onClick = { method = dev.montb.basicphone.util.VoicemailNumbers.Method.FULL_NUMBER },
                        label = { Text("Full number") }
                    )
                }
                TextButton(
                    onClick = {
                        dev.montb.basicphone.util.VoicemailNumbers
                            .suggest(context, subId, carrierName, method)
                            ?.let { number = it }
                    },
                    modifier = Modifier.padding(top = 2.dp)
                ) { Text("Auto-detect number") }
                Text(
                    "Tip: *86 works across carriers and MVNOs (e.g. Mint on T-Mobile). " +
                        "Switch to a full number only if the shortcut fails.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = number,
                    onValueChange = { number = it },
                    label = { Text("Number") },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
                )
                Text(
                    "Optional PIN — auto-sent as tones after the call connects, so you " +
                        "don't have to type it each time. Leave blank to enter it manually.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 12.dp)
                )
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it },
                    label = { Text("PIN (optional)") },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { if (number.isNotBlank()) onSave(number, pin) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun ErrorState(modifier: Modifier, onRetry: () -> Unit) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Call log permission is needed to show your history.")
        TextButton(onClick = onRetry) { Text("Grant / Retry") }
    }
}

/** Confirm before adding a number to the system block list. */
@Composable
private fun BlockConfirmDialog(number: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Block number?") },
        text = {
            Text(
                "Block $number? You won't get calls or texts from it. " +
                    "You can unblock it later from \"Blocked numbers\" in the menu."
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Block") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** Lists the system-blocked numbers with a per-row Unblock action. */
@Composable
private fun BlockedNumbersDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var entries by remember { mutableStateOf(Blocking.list(context)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Blocked numbers") },
        text = {
            if (entries.isEmpty()) {
                Text("No blocked numbers. Long-press a call and choose \"Block number\" to add one.")
            } else {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    entries.forEach { e ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                e.number.ifBlank { "Unknown" },
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyLarge
                            )
                            TextButton(onClick = {
                                if (Blocking.unblock(context, e.number)) entries = Blocking.list(context)
                            }) { Text("Unblock") }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@Composable
private fun EmptyState(modifier: Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Text("No calls yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun typeIcon(type: Int): Pair<ImageVector, androidx.compose.ui.graphics.Color> = when (type) {
    CallLog.Calls.INCOMING_TYPE -> Icons.AutoMirrored.Filled.CallReceived to androidx.compose.ui.graphics.Color(0xFF2E7D32)
    CallLog.Calls.OUTGOING_TYPE -> Icons.AutoMirrored.Filled.CallMade to androidx.compose.ui.graphics.Color(0xFF1565C0)
    CallLog.Calls.MISSED_TYPE -> Icons.AutoMirrored.Filled.CallMissed to androidx.compose.ui.graphics.Color(0xFFC62828)
    CallLog.Calls.VOICEMAIL_TYPE -> Icons.Filled.Voicemail to androidx.compose.ui.graphics.Color(0xFF6A1B9A)
    else -> Icons.Filled.Block to androidx.compose.ui.graphics.Color(0xFF757575)
}

/** Absolute date + time for a call, e.g. "Today, 2:34 PM", "Yesterday, 9:05 AM",
 *  "Aug 15, 2:34 PM", or "Aug 15, 2025, 2:34 PM". The time follows the device's 12/24h setting. */
private fun formatCallTime(context: android.content.Context, millis: Long): String {
    val time = DateFormat.getTimeFormat(context).format(java.util.Date(millis))
    val datePart = when {
        DateUtils.isToday(millis) -> "Today"
        DateUtils.isToday(millis + DateUtils.DAY_IN_MILLIS) -> "Yesterday"
        else -> {
            val now = java.util.Calendar.getInstance()
            val then = java.util.Calendar.getInstance().apply { timeInMillis = millis }
            val pattern = if (now.get(java.util.Calendar.YEAR) == then.get(java.util.Calendar.YEAR))
                "MMM d" else "MMM d, yyyy"
            java.text.SimpleDateFormat(pattern, java.util.Locale.getDefault())
                .format(java.util.Date(millis))
        }
    }
    return "$datePart, $time"
}

private fun formatDuration(seconds: Long): String {
    val m = seconds / 60
    val s = seconds % 60
    return "%d:%02d".format(m, s)
}

@Composable
fun SpamScreeningDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var mode by remember { mutableStateOf(Prefs.screeningMode(context)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Spam call screening") },
        text = {
            Column {
                Text(
                    "Uses the carrier's STIR/SHAKEN verification — the same ✓/⚠ signal shown " +
                        "during a call — to catch spoofed numbers. Only calls that FAIL " +
                        "verification are affected; everything else rings normally. All on-device, " +
                        "no number lists or network.",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.size(10.dp))
                ScreeningOption("Off", "Screen nothing.",
                    mode == Prefs.ScreeningMode.OFF) { mode = Prefs.ScreeningMode.OFF }
                ScreeningOption("Silence", "Let failed calls through, but don't ring or vibrate.",
                    mode == Prefs.ScreeningMode.SILENCE) { mode = Prefs.ScreeningMode.SILENCE }
                ScreeningOption("Reject", "Block failed calls outright (still kept in the call log).",
                    mode == Prefs.ScreeningMode.REJECT) { mode = Prefs.ScreeningMode.REJECT }
            }
        },
        confirmButton = {
            TextButton(onClick = { Prefs.setScreeningMode(context, mode); onDismiss() }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun ScreeningOption(label: String, desc: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp)
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(modifier = Modifier.padding(start = 4.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun SearchEngineDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var engine by remember { mutableStateOf(Prefs.searchEngine(context)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Number lookup search") },
        text = {
            Column {
                Text(
                    "Which search engine opens when you long-press a call and choose " +
                        "\"Look up number online\". DuckDuckGo doesn't profile your searches.",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.size(10.dp))
                Prefs.SearchEngine.values().forEach { option ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                            .clickable { engine = option }
                            .padding(vertical = 4.dp)
                    ) {
                        RadioButton(selected = engine == option, onClick = { engine = option })
                        Text(
                            option.label,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(start = 4.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { Prefs.setSearchEngine(context, engine); onDismiss() }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** True when READ_CALL_LOG is granted (auto-granted to the default dialer, or via the prompt). */
private fun hasCallLogPermission(context: Context): Boolean =
    context.checkSelfPermission(Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED
