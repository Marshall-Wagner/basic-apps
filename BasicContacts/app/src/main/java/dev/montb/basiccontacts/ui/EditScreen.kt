package dev.montb.basiccontacts.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.montb.basiccontacts.data.EditableContact
import dev.montb.basiccontacts.data.LabeledValue
import dev.montb.basiccontacts.util.Images
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Add (contactId == null) or edit an existing contact. Phones/emails are dynamic lists
 * the user can grow. On save, the ViewModel inserts or updates the system provider.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditContactScreen(
    vm: ContactsViewModel,
    contactId: Long?,
    onDone: () -> Unit,
    onCancel: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }

    // Load existing contact (edit) or start blank (add).
    val loaded by produceState<EditableContact?>(initialValue = null, key1 = contactId) {
        value = if (contactId == null) EditableContact() else vm.editable(contactId)
    }
    val initial = loaded ?: return  // brief spinner-free wait; recomposes when ready

    var name by remember(initial) { mutableStateOf(initial.displayName) }
    var phones by remember(initial) { mutableStateOf(initial.phones.ifEmpty {
        listOf(LabeledValue("", EditableContact.PHONE_MOBILE))
    }) }
    var emails by remember(initial) { mutableStateOf(initial.emails) }
    var note by remember(initial) { mutableStateOf(initial.note) }

    // Photo edit state: existing uri (display), freshly picked bytes, or cleared.
    var newPhoto by remember(initial) { mutableStateOf<ByteArray?>(null) }
    var photoCleared by remember(initial) { mutableStateOf(false) }

    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) scope.launch {
            val bytes = vm.readPickedPhoto(uri)
            if (bytes != null) { newPhoto = bytes; photoCleared = false }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (contactId == null) "New contact" else "Edit contact") },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Cancel")
                    }
                },
                actions = {
                    IconButton(
                        enabled = !saving && name.isNotBlank(),
                        onClick = {
                            saving = true
                            scope.launch {
                                val ok = vm.save(
                                    EditableContact(
                                        rawContactId = initial.rawContactId,
                                        displayName = name,
                                        phones = phones,
                                        emails = emails,
                                        note = note,
                                        photoUri = initial.photoUri,
                                        newPhoto = newPhoto,
                                        photoCleared = photoCleared
                                    )
                                )
                                saving = false
                                if (ok) onDone()
                            }
                        }
                    ) {
                        Icon(Icons.Filled.Check, contentDescription = "Save")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Photo picker: tap the avatar to choose an image; remove if one is set.
            val hasPhoto = newPhoto != null || (initial.photoUri != null && !photoCleared)
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                EditableAvatar(
                    name = name,
                    newPhoto = newPhoto,
                    existingUri = if (photoCleared) null else initial.photoUri,
                    onClick = { photoPicker.launch("image/*") }
                )
                Row {
                    TextButton(onClick = { photoPicker.launch("image/*") }) {
                        Text(if (hasPhoto) "Change photo" else "Add photo")
                    }
                    if (hasPhoto) {
                        TextButton(onClick = { newPhoto = null; photoCleared = true }) {
                            Text("Remove")
                        }
                    }
                }
            }

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            SectionLabel("Phone numbers")
            phones.forEachIndexed { i, p ->
                ValueRow(
                    value = p.value,
                    placeholder = "Phone",
                    keyboardType = KeyboardType.Phone,
                    onChange = { phones = phones.replaceAt(i, p.copy(value = it)) },
                    onRemove = { phones = phones.removeAt(i) }
                )
            }
            AddButton("Add phone") {
                phones = phones + LabeledValue("", EditableContact.PHONE_MOBILE)
            }

            SectionLabel("Emails")
            emails.forEachIndexed { i, e ->
                ValueRow(
                    value = e.value,
                    placeholder = "Email",
                    keyboardType = KeyboardType.Email,
                    onChange = { emails = emails.replaceAt(i, e.copy(value = it)) },
                    onRemove = { emails = emails.removeAt(i) }
                )
            }
            AddButton("Add email") {
                emails = emails + LabeledValue("", EditableContact.EMAIL_HOME)
            }

            SectionLabel("Note")
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text("Note") },
                modifier = Modifier.fillMaxWidth(),
                maxLines = 4
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = androidx.compose.material3.MaterialTheme.typography.labelLarge)
}

/** Tappable avatar for the edit screen: previews freshly picked bytes, else the
 *  existing photo URI, else a monogram. */
@Composable
private fun EditableAvatar(
    name: String,
    newPhoto: ByteArray?,
    existingUri: String?,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(initialValue = null, key1 = newPhoto, key2 = existingUri) {
        value = withContext(Dispatchers.IO) {
            when {
                newPhoto != null -> Images.decodeBytes(newPhoto)?.asImageBitmap()
                existingUri != null -> Images.decodeUri(context, existingUri)?.asImageBitmap()
                else -> null
            }
        }
    }
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.size(96.dp).clickable(onClick = onClick)
    ) {
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bitmap = bmp,
                contentDescription = "Contact photo",
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(96.dp).clip(CircleShape)
            )
        } else {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    name.firstOrNull()?.uppercase() ?: "?",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ValueRow(
    value: String,
    placeholder: String,
    keyboardType: KeyboardType,
    onChange: (String) -> Unit,
    onRemove: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            placeholder = { Text(placeholder) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = onRemove) {
            Icon(Icons.Filled.Close, contentDescription = "Remove")
        }
    }
}

@Composable
private fun AddButton(text: String, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Icon(Icons.Filled.Add, contentDescription = null)
        Text("  $text")
    }
}

private fun <T> List<T>.replaceAt(index: Int, value: T): List<T> =
    toMutableList().also { it[index] = value }

private fun <T> List<T>.removeAt(index: Int): List<T> =
    toMutableList().also { it.removeAt(index) }
