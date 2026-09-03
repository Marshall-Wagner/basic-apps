package dev.montb.basiccontacts.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.ContactsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import dev.montb.basiccontacts.data.ContactSummary

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefillPhone = insertPhoneFrom(intent)
        setContent {
            BasicContactsTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val vm: ContactsViewModel = viewModel()
                    AppRoot(vm, prefillPhone)
                }
            }
        }
    }

    // A fresh "add contact" hand-off can arrive while we're already open (singleTop);
    // re-run onCreate so the prefilled editor comes up.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        recreate()
    }
}

/** The phone number from an ACTION_INSERT / ACTION_INSERT_OR_EDIT contact intent, else null. */
private fun insertPhoneFrom(intent: Intent?): String? {
    if (intent?.action != Intent.ACTION_INSERT && intent?.action != Intent.ACTION_INSERT_OR_EDIT) return null
    return intent.getStringExtra(ContactsContract.Intents.Insert.PHONE)?.takeIf { it.isNotBlank() }
}

private sealed interface Nav {
    data object List : Nav
    data class Detail(val contact: ContactSummary) : Nav
    data class Edit(val contactId: Long?, val prefillPhone: String? = null) : Nav  // null id = new
}

@Composable
private fun AppRoot(vm: ContactsViewModel, initialPrefillPhone: String? = null) {
    val context = LocalContext.current
    val activity = context as? Activity
    // Launched from another app's "add contact" (BasicSms / dialer): open straight into a
    // prefilled new-contact editor, and finish back to the caller when done.
    val startedForInsert = initialPrefillPhone != null

    fun hasContactsPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    var granted by remember { mutableStateOf(hasContactsPermission()) }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        granted = hasContactsPermission()
        if (granted) vm.refresh()
    }

    if (!granted) {
        PermissionScreen {
            permLauncher.launch(
                arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
            )
        }
        return
    }

    // Refresh on resume so externally-changed contacts (e.g. from BasicSms' add-contact)
    // show up when returning to this app.
    val lifecycleOwner = LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) vm.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Import/export pickers (SAF, no storage permission needed).
    val importPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) vm.importVcf(uri) }
    val exportPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/x-vcard")
    ) { uri -> if (uri != null) vm.exportVcf(uri) }

    var nav: Nav by remember {
        mutableStateOf<Nav>(if (startedForInsert) Nav.Edit(null, initialPrefillPhone) else Nav.List)
    }
    BackHandler(enabled = nav != Nav.List) {
        if (startedForInsert) activity?.finish() else nav = Nav.List
    }

    when (val current = nav) {
        is Nav.List -> ContactListScreen(
            vm,
            onOpen = { nav = Nav.Detail(it) },
            onNew = { nav = Nav.Edit(null) },
            onImport = { importPicker.launch(arrayOf("text/x-vcard", "text/vcard", "*/*")) },
            onExport = { exportPicker.launch("contacts.vcf") }
        )
        is Nav.Detail -> ContactDetailScreen(
            vm, current.contact,
            onBack = { nav = Nav.List },
            onEdit = { nav = Nav.Edit(current.contact.contactId) },
            onDeleted = { nav = Nav.List }
        )
        is Nav.Edit -> EditContactScreen(
            vm, current.contactId, current.prefillPhone,
            onDone = { if (startedForInsert) activity?.finish() else nav = Nav.List },
            onCancel = { if (startedForInsert) activity?.finish() else nav = Nav.List }
        )
    }

    TransferDialog(vm)
}

@Composable
private fun TransferDialog(vm: ContactsViewModel) {
    val state by vm.transfer.collectAsState()
    when (val s = state) {
        ContactsViewModel.TransferState.Idle -> Unit
        ContactsViewModel.TransferState.Running -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Working…") },
            text = {
                androidx.compose.foundation.layout.Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(modifier = Modifier.padding(end = 16.dp))
                    Text("Transferring contacts.")
                }
            },
            confirmButton = {}
        )
        is ContactsViewModel.TransferState.Done -> AlertDialog(
            onDismissRequest = { vm.clearTransfer() },
            title = { Text("Done") },
            text = { Text(s.message) },
            confirmButton = { TextButton(onClick = { vm.clearTransfer() }) { Text("OK") } }
        )
    }
}

@Composable
private fun PermissionScreen(onGrant: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Basic Contacts", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(12.dp))
        Text(
            "This app reads and edits your phone's contacts. Grant contacts access to continue.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onGrant) { Text("Allow contacts access") }
    }
}
