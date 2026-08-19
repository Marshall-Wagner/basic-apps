package dev.montb.basiccontacts.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.montb.basiccontacts.data.ContactDetail
import dev.montb.basiccontacts.data.ContactSummary
import dev.montb.basiccontacts.util.Actions
import dev.montb.basiccontacts.util.Images
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactListScreen(
    vm: ContactsViewModel,
    onOpen: (ContactSummary) -> Unit,
    onNew: () -> Unit,
    onImport: () -> Unit,
    onExport: () -> Unit
) {
    val contacts by vm.contacts.collectAsState()
    val query by vm.query.collectAsState()
    var menuOpen by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Contacts") },
                actions = {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "More")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Import from vCard") },
                            onClick = { menuOpen = false; onImport() }
                        )
                        DropdownMenuItem(
                            text = { Text("Export to vCard") },
                            onClick = { menuOpen = false; onExport() }
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onNew) {
                Icon(Icons.Filled.Add, contentDescription = "New contact")
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = query,
                onValueChange = vm::onQueryChange,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                placeholder = { Text("Search name or number") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true
            )
            if (contacts.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (query.isBlank()) "No contacts yet" else "No matches",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(contacts, key = { it.contactId }) { c ->
                        ContactRow(c, onClick = { onOpen(c) })
                    }
                }
            }
        }
    }
}

@Composable
private fun ContactRow(c: ContactSummary, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Avatar(c.displayName, c.photoUri, size = 40.dp)
        Spacer(Modifier.size(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(c.displayName, style = MaterialTheme.typography.titleMedium)
            if (c.primaryNumber != null) {
                Text(
                    c.primaryNumber,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * Contact avatar: shows the photo if there is one, else a monogram (first letter).
 * Photo decoding is downsampled off the main thread; no image-loading library.
 */
@Composable
private fun Avatar(name: String, photoUri: String?, size: Dp) {
    val context = LocalContext.current
    // Cache only the small list thumbnails (size < 72dp); the big detail avatar is a
    // one-off so it skips the cache to avoid holding large bitmaps in memory.
    val cache = size < 72.dp
    val bitmap by produceState<ImageBitmap?>(initialValue = null, key1 = photoUri) {
        value = if (photoUri == null) null else withContext(Dispatchers.IO) {
            Images.decodeUri(context, photoUri, useCache = cache)?.asImageBitmap()
        }
    }
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.size(size)
    ) {
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bitmap = bmp,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size).clip(CircleShape)
            )
        } else {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    name.firstOrNull()?.uppercase() ?: "?",
                    style = if (size >= 72.dp) MaterialTheme.typography.headlineMedium
                    else MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactDetailScreen(
    vm: ContactsViewModel,
    summary: ContactSummary,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onDeleted: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirmDelete by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }

    val detail by produceState<ContactDetail?>(initialValue = null, key1 = summary.contactId) {
        value = vm.detail(summary.contactId)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(detail?.displayName ?: summary.displayName) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Filled.Edit, contentDescription = "Edit")
                    }
                    IconButton(onClick = { confirmDelete = true }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete")
                    }
                }
            )
        }
    ) { padding ->
        val d = detail
        if (d == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Large photo/monogram header.
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Avatar(d.displayName, d.photoUri, size = 96.dp)
                }
                Spacer(Modifier.height(12.dp))
                d.phones.forEach { p ->
                    DetailRow(
                        label = p.label, value = p.value,
                        actions = {
                            IconButton(onClick = { Actions.dial(context, p.value) }) {
                                Icon(Icons.Filled.Call, contentDescription = "Call")
                            }
                            IconButton(onClick = { Actions.text(context, p.value) }) {
                                Icon(Icons.AutoMirrored.Filled.Message, contentDescription = "Text")
                            }
                        }
                    )
                }
                d.emails.forEach { e ->
                    DetailRow(
                        label = e.label, value = e.value,
                        actions = {
                            IconButton(onClick = { Actions.email(context, e.value) }) {
                                Icon(Icons.Filled.Email, contentDescription = "Email")
                            }
                        }
                    )
                }
                d.note?.takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.height(8.dp))
                    Text("Note", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(it, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { if (!deleting) confirmDelete = false },
            title = { Text("Delete contact?") },
            text = { Text("Remove ${detail?.displayName ?: summary.displayName} from your phone?") },
            confirmButton = {
                TextButton(
                    enabled = !deleting,
                    onClick = {
                        deleting = true
                        scope.launch {
                            vm.delete(summary.contactId, detail?.lookupKey ?: summary.lookupKey)
                            confirmDelete = false
                            onDeleted()
                        }
                    }
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(
                    enabled = !deleting,
                    onClick = { confirmDelete = false }
                ) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun DetailRow(label: String, value: String, actions: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodyLarge)
        }
        actions()
    }
}
