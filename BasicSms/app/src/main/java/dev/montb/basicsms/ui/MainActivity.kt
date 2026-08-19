package dev.montb.basicsms.ui

import android.Manifest
import android.os.Build
import android.os.Bundle
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
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.montb.basicsms.util.DefaultAppHelper

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val initialAddress = intent?.getStringExtra(EXTRA_ADDRESS)
        setContent {
            BasicSmsTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val vm: MessagesViewModel = viewModel()
                    AppRoot(vm, initialAddress)
                }
            }
        }
    }

    companion object {
        const val EXTRA_ADDRESS = "address"
    }
}

private sealed interface Nav {
    data object List : Nav
    data class Thread(val address: String) : Nav
    data object New : Nav
}

@Composable
private fun AppRoot(vm: MessagesViewModel, initialAddress: String?) {
    val context = LocalContext.current
    var isDefault by remember { mutableStateOf(DefaultAppHelper.isDefaultSmsApp(context)) }
    var hasDrift by remember { mutableStateOf(DefaultAppHelper.hasDefaultSmsDrift(context)) }

    val roleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        isDefault = DefaultAppHelper.isDefaultSmsApp(context)
        hasDrift = DefaultAppHelper.hasDefaultSmsDrift(context)
    }

    // Re-check on resume: the legacy default-SMS value can drift to null while we're
    // backgrounded (OEM power management), so don't trust the first read forever.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isDefault = DefaultAppHelper.isDefaultSmsApp(context)
                hasDrift = DefaultAppHelper.hasDefaultSmsDrift(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (!isDefault) {
        SetupScreen {
            roleLauncher.launch(DefaultAppHelper.requestDefaultSmsIntent(context))
        }
        return
    }

    // We hold the SMS role but the legacy default-package value disagrees, on this
    // ROM that can stop SMS_DELIVER from reaching us. Warn and let the user re-run the
    // default-app prompt, which re-syncs the legacy value.
    if (hasDrift) {
        DriftWarningScreen(
            onFix = { roleLauncher.launch(DefaultAppHelper.requestDefaultSmsIntent(context)) },
            onIgnore = { hasDrift = false }
        )
        return
    }

    // Once we're the default app, make sure we have runtime permissions and (for
    // reliability against the ROG's task-killer) battery-optimization exemption.
    val batteryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (!DefaultAppHelper.isIgnoringBatteryOptimizations(context)) {
            batteryLauncher.launch(DefaultAppHelper.requestIgnoreBatteryOptimizationsIntent(context))
        }
    }
    LaunchedEffect(Unit) { permLauncher.launch(requiredPermissions()) }

    var nav: Nav by remember {
        mutableStateOf(initialAddress?.let { Nav.Thread(it) } ?: Nav.List)
    }

    // System back button / gesture mirrors the top-left arrow: from a Thread/New screen
    // it returns to the conversation list. On the list itself it's disabled, so the OS
    // handles it normally (exits the app).
    BackHandler(enabled = nav != Nav.List) { nav = Nav.List }

    when (val current = nav) {
        is Nav.List -> ConversationListScreen(
            vm,
            onOpen = { nav = Nav.Thread(it) },
            onNew = { nav = Nav.New }
        )
        is Nav.Thread -> {
            LaunchedEffect(current.address) { vm.markRead(current.address) }
            ThreadScreen(vm, current.address, onBack = { nav = Nav.List })
        }
        is Nav.New -> NewMessageScreen(
            onCancel = { nav = Nav.List },
            onStart = { nav = Nav.Thread(it) }
        )
    }
}

@Composable
private fun SetupScreen(onSetDefault: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Basic SMS", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(12.dp))
        Text(
            "To send and reliably receive texts in the background, set this as your " +
                "default SMS app.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onSetDefault) { Text("Set as default SMS app") }
    }
}

@Composable
private fun DriftWarningScreen(onFix: () -> Unit, onIgnore: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Receiving may be broken", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(12.dp))
        Text(
            "This app holds the SMS role, but the system's default-SMS setting is out " +
                "of sync. On this phone that can stop incoming texts from arriving. " +
                "Re-confirm the default-app prompt to fix it.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onFix) { Text("Re-confirm default SMS app") }
        Spacer(Modifier.height(8.dp))
        Button(onClick = onIgnore) { Text("Continue anyway") }
    }
}

private fun requiredPermissions(): Array<String> {
    val list = mutableListOf(
        Manifest.permission.SEND_SMS,
        Manifest.permission.RECEIVE_SMS,
        Manifest.permission.READ_SMS,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.READ_PHONE_STATE
    )
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        list += Manifest.permission.POST_NOTIFICATIONS
    }
    return list.toTypedArray()
}
