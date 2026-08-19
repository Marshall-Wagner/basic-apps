package dev.montb.basicsms.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import dev.montb.basicsms.data.MessageEntity
import dev.montb.basicsms.util.Clip
import dev.montb.basicsms.util.ContactNames
import dev.montb.basicsms.util.Contacts
import dev.montb.basicsms.util.Sims
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationListScreen(
    vm: MessagesViewModel,
    onOpen: (String) -> Unit,
    onNew: () -> Unit
) {
    val conversations by vm.conversations.collectAsState()
    val importState by vm.importState.collectAsState()

    // Pick the backup .zip from storage (no storage permission needed: SAF/document picker).
    val pickZip = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) vm.importBackup(uri) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Messages") },
                actions = {
                    IconButton(onClick = {
                        // application/zip plus a fallback wildcard for pickers that
                        // report the .zip with an odd MIME type.
                        pickZip.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                    }) {
                        Icon(Icons.Filled.Download, contentDescription = "Import backup")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onNew) {
                Icon(Icons.Filled.Add, contentDescription = "New message")
            }
        }
    ) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
            item { NotificationsDisabledBanner() }
            items(conversations, key = { it.address }) { c ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpen(c.address) }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        val name = rememberContactName(c.address)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                name ?: c.address,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                conversationStamp(c.lastTimestamp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            c.lastBody,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (c.unreadCount > 0) {
                        Spacer(Modifier.width(8.dp))
                        Badge { Text(c.unreadCount.toString()) }
                    }
                }
            }
        }
    }

    ImportStatusDialog(importState, onDismiss = { vm.clearImportState() })
}

/**
 * Warns when notifications are switched off for this app (POST_NOTIFICATIONS denied, or the
 * channel/app toggled off in system settings). When that's the case the SMS_DELIVER receiver
 * still stores incoming texts, but [Notifier] can't post anything, so a code (e.g. a PayPal
 * login OTP) arrives with no heads-up while the app is closed. This is the silent failure that
 * matches "it won't notify me." One tap opens the system notification settings to re-enable.
 * Re-checked on resume so the banner disappears once the user flips it back on.
 */
@Composable
private fun NotificationsDisabledBanner() {
    val context = LocalContext.current
    var enabled by remember {
        mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled())
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                enabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    if (enabled) return

    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier
                .clickable {
                    context.startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    )
                }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Notifications are off",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Text(
                    "You won't be alerted to incoming texts (like login codes) while the " +
                        "app is closed. Tap to turn notifications back on.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    }
}

@Composable
private fun ImportStatusDialog(
    state: MessagesViewModel.ImportState,
    onDismiss: () -> Unit
) {
    when (state) {
        is MessagesViewModel.ImportState.Idle -> Unit

        is MessagesViewModel.ImportState.Running -> AlertDialog(
            onDismissRequest = { /* not cancelable while running */ },
            title = { Text("Importing…") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.padding(end = 16.dp))
                    Text("Reading your backup and adding messages.")
                }
            },
            confirmButton = {}
        )

        is MessagesViewModel.ImportState.Done -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Import complete") },
            text = {
                Text(
                    "Imported ${state.result.imported} messages.\n" +
                        "Skipped ${state.result.skipped} duplicates.\n" +
                        (if (state.result.errors > 0) "Couldn't read ${state.result.errors} lines." else "")
                )
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } }
        )

        is MessagesViewModel.ImportState.Failed -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Import failed") },
            text = { Text(state.message) },
            confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThreadScreen(
    vm: MessagesViewModel,
    address: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val sims = remember { Sims.active(context) }
    val subToCarrier = remember(sims) { sims.associate { it.subId to it.carrier } }
    val messages = remember(address) { vm.thread(address) }.collectAsLazyPagingItems()

    // Mark this thread read + dismiss its notification not only on open (the caller's
    // address-keyed effect) but whenever a new message arrives while we're already viewing
    // it. Gated to RESUMED so a message that lands while the app is backgrounded keeps its
    // notification until the user actually returns to the thread.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner, address) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            snapshotFlow { messages.itemCount }
                .distinctUntilChanged()
                .collect { count -> if (count > 0) vm.markRead(address) }
        }
    }

    var draft by remember { mutableStateOf("") }
    var selectedSub by remember(sims) { mutableStateOf(sims.firstOrNull()?.subId ?: -1) }

    // Bump after returning from the Contacts app so the name re-resolves (drops the
    // "Add contact" button once the number is saved).
    var contactRefresh by remember { mutableStateOf(0) }
    val addContact = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        ContactNames.invalidate()
        contactRefresh++
    }
    val contactName = rememberContactName(address, refreshKey = contactRefresh)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(contactName ?: address) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Only offer "add contact" for unknown numbers.
                    if (contactName == null) {
                        IconButton(onClick = {
                            addContact.launch(Contacts.insertContactIntent(address))
                        }) {
                            Icon(Icons.Filled.PersonAdd, contentDescription = "Add contact")
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // reverseLayout so newest is at the bottom; pairs with DESC query order.
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
                reverseLayout = true,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(
                    count = messages.itemCount,
                    key = messages.itemKey { it.id }
                ) { index ->
                    val msg = messages[index] ?: return@items
                    MessageBubble(msg, subToCarrier)
                    // List is DESC + reverseLayout, so the visually-PREVIOUS (older)
                    // message is at index+1. When it's a different day, show a date
                    // divider here, it renders just above the start of a new day.
                    val older = if (index + 1 < messages.itemCount) messages[index + 1] else null
                    if (older == null || differentDay(msg.timestamp, older.timestamp)) {
                        DateDivider(formatDateDivider(msg.timestamp))
                    }
                }
            }

            // SIM picker, only on a dual-SIM device.
            if (sims.size > 1) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
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

            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Text message") },
                    maxLines = 5
                )
                IconButton(
                    onClick = {
                        val text = draft.trim()
                        if (text.isNotEmpty()) {
                            vm.send(address, text, selectedSub)
                            draft = ""
                        }
                    }
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                }
            }
        }
    }
}

/**
 * Resolves [address] to a contact display name (off the main thread). Returns the
 * resolved name, or null if there's no matching contact. [refreshKey] forces a
 * re-query (e.g. after the user adds a new contact). Callers fall back to the raw
 * address when this is null.
 */
@Composable
private fun rememberContactName(address: String, refreshKey: Any = Unit): String? {
    val context = LocalContext.current
    val name by produceState<String?>(initialValue = null, key1 = address, key2 = refreshKey) {
        value = withContext(Dispatchers.IO) { ContactNames.lookup(context, address) }
    }
    return name
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(message: MessageEntity, subToCarrier: Map<Int, String>) {
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    val mine = !message.incoming
    // Only show the carrier line when there's more than one SIM to disambiguate.
    val carrier = subToCarrier[message.subId]?.takeIf { subToCarrier.size > 1 }
    // Decode the MMS image once (off the main thread) when this row has an attachment.
    val imageBitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(
        initialValue = null, key1 = message.attachmentPath
    ) {
        val path = message.attachmentPath
        value = if (path != null && message.attachmentMime?.startsWith("image/") == true) {
            withContext(Dispatchers.IO) {
                runCatching {
                    BitmapFactory.decodeFile(path)?.asImageBitmap()
                }.getOrNull()
            }
        } else null
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Surface(
                color = if (mine) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .align(if (mine) Alignment.CenterEnd else Alignment.CenterStart)
                    // Long-press a message to copy its text (e.g. a login code).
                    .combinedClickable(
                        onClick = {},
                        onLongClick = { if (message.body.isNotBlank()) menuOpen = true }
                    )
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    val bmp = imageBitmap
                    when {
                        bmp != null -> Image(
                            bitmap = bmp,
                            contentDescription = "MMS image",
                            modifier = Modifier
                                .heightIn(max = 220.dp)
                                .clip(RoundedCornerShape(12.dp))
                        )
                        // Attachment exists but isn't a decodable image (video/audio/etc.)
                        message.attachmentPath != null -> Text("📎 ${message.attachmentMime ?: "attachment"}")
                    }
                    if (message.body.isNotBlank()) {
                        if (imageBitmap != null || message.attachmentPath != null) {
                            Spacer(Modifier.height(6.dp))
                        }
                        Text(message.body)
                    }
                }
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Copy text") },
                    onClick = { menuOpen = false; Clip.copy(context, "Message", message.body) }
                )
            }
        }
        // Footer under each bubble: time (always) + "via carrier" (dual-SIM only).
        val footer = buildString {
            append(formatTime(message.timestamp))
            if (carrier != null) append("  ·  via $carrier")
        }
        Text(
            footer,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .align(if (mine) Alignment.End else Alignment.Start)
                .padding(horizontal = 4.dp, vertical = 1.dp)
        )
    }
}

/** Local clock time for a message, e.g. "9:14 AM". */
private fun formatTime(epochMillis: Long): String =
    java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault())
        .format(java.util.Date(epochMillis))

/** Compact stamp for the conversation list: the time when the last message is from today,
 *  otherwise the date (the year is shown only when it isn't the current year). */
private fun conversationStamp(epochMillis: Long): String {
    if (epochMillis <= 0L) return ""
    val msg = java.util.Calendar.getInstance().apply { timeInMillis = epochMillis }
    val now = java.util.Calendar.getInstance()
    val pattern = when {
        msg.get(java.util.Calendar.YEAR) == now.get(java.util.Calendar.YEAR) &&
            msg.get(java.util.Calendar.DAY_OF_YEAR) == now.get(java.util.Calendar.DAY_OF_YEAR) -> "h:mm a"
        msg.get(java.util.Calendar.YEAR) == now.get(java.util.Calendar.YEAR) -> "MMM d"
        else -> "MMM d, yyyy"
    }
    return java.text.SimpleDateFormat(pattern, java.util.Locale.getDefault())
        .format(java.util.Date(epochMillis))
}

/** A day label for the divider, e.g. "Today", "Yesterday", or "May 28, 2026". */
private fun formatDateDivider(epochMillis: Long): String {
    val msgCal = java.util.Calendar.getInstance().apply { timeInMillis = epochMillis }
    val now = java.util.Calendar.getInstance()
    fun sameDay(a: java.util.Calendar, b: java.util.Calendar) =
        a.get(java.util.Calendar.YEAR) == b.get(java.util.Calendar.YEAR) &&
            a.get(java.util.Calendar.DAY_OF_YEAR) == b.get(java.util.Calendar.DAY_OF_YEAR)
    val yesterday = (now.clone() as java.util.Calendar).apply {
        add(java.util.Calendar.DAY_OF_YEAR, -1)
    }
    return when {
        sameDay(msgCal, now) -> "Today"
        sameDay(msgCal, yesterday) -> "Yesterday"
        else -> java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.getDefault())
            .format(java.util.Date(epochMillis))
    }
}

/** True if [a] and [b] fall on different calendar days (drives the date divider). */
private fun differentDay(a: Long, b: Long): Boolean {
    val ca = java.util.Calendar.getInstance().apply { timeInMillis = a }
    val cb = java.util.Calendar.getInstance().apply { timeInMillis = b }
    return ca.get(java.util.Calendar.YEAR) != cb.get(java.util.Calendar.YEAR) ||
        ca.get(java.util.Calendar.DAY_OF_YEAR) != cb.get(java.util.Calendar.DAY_OF_YEAR)
}

@Composable
private fun DateDivider(label: String) {
    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewMessageScreen(
    onCancel: () -> Unit,
    onStart: (String) -> Unit
) {
    var number by remember { mutableStateOf("") }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("New message") },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Cancel")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            OutlinedTextField(
                value = number,
                onValueChange = { number = it },
                label = { Text("Phone number") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                modifier = Modifier.fillMaxWidth()
            )
            TextButton(
                onClick = { if (number.isNotBlank()) onStart(number.trim()) },
                modifier = Modifier.align(Alignment.End).padding(top = 12.dp)
            ) { Text("Start conversation") }
        }
    }
}
