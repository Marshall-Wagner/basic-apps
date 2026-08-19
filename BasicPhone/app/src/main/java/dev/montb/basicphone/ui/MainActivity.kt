package dev.montb.basicphone.ui

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.montb.basicphone.util.DialerRoleHelper
import dev.montb.basicphone.util.Prefs
import dev.montb.basicphone.util.Sims
import dev.montb.basicphone.util.TelephonyInfo
import dev.montb.basicphone.util.callVoicemail
import dev.montb.basicphone.util.placeCall

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            BasicPhoneTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val vm: DialerViewModel = viewModel()
                    PhoneApp(vm)
                }
            }
        }
    }
}

@Composable
private fun PhoneApp(vm: DialerViewModel) {
    val context = LocalContext.current

    // (Missed-call clearing lives in HomeScreen now, it clears on resume AND when a new
    // call is logged while the dialer is foreground, gated to RESUMED.)

    // Exempt us from Doze/battery optimization (CN ROM kills background work otherwise).
    // Chained after permissions, mirroring BasicSms. Self-heals if the exemption is
    // ever cleared, since this fires whenever the app starts and we're not yet exempt.
    val batteryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (!DialerRoleHelper.isIgnoringBatteryOptimizations(context)) {
            batteryLauncher.launch(DialerRoleHelper.requestIgnoreBatteryOptimizationsIntent(context))
        }
    }
    LaunchedEffect(Unit) { permLauncher.launch(requiredPermissions()) }

    var isDefault by remember { mutableStateOf(DialerRoleHelper.isDefaultDialer(context)) }
    val roleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { isDefault = DialerRoleHelper.isDefaultDialer(context) }

    val volte = remember { TelephonyInfo.carrierVolteAvailable(context) }

    val sims = remember { Sims.active(context) }
    var showKeypad by remember { mutableStateOf(false) }
    // subId currently being set up (-1 = the legacy/global box). null = dialog closed.
    var voicemailSetupSub by remember { mutableStateOf<Int?>(null) }
    // When >1 SIM, tapping voicemail first asks which SIM's box to call.
    var showVoicemailSimPicker by remember { mutableStateOf(false) }
    // Pending call-log call awaiting the user's "Call?" confirmation (manual taps only).
    var pendingCall by remember { mutableStateOf<CallTarget?>(null) }

    // Call (or set up) the voicemail box for a specific SIM. subId -1 = default/global.
    fun voicemailFor(subId: Int) {
        // Robust: saved number -> voicemail dial-scheme -> prompt setup.
        // Never silently dials the (broken) system voicemail number.
        if (!callVoicemail(context, subId)) voicemailSetupSub = subId
    }

    HomeScreen(
        vm = vm,
        volteAvailable = volte,
        isDefault = isDefault,
        onOpenKeypad = { showKeypad = true },
        onMakeDefault = { roleLauncher.launch(DialerRoleHelper.requestIntent(context)) },
        onVoicemail = {
            // One SIM (or none): call its box directly. Multiple SIMs: ask which one.
            if (sims.size > 1) showVoicemailSimPicker = true
            else voicemailFor(sims.firstOrNull()?.subId ?: -1)
        },
        onVoicemailSetup = {
            // From the menu: configure the box (ask which SIM first if multi-SIM).
            if (sims.size > 1) showVoicemailSimPicker = true
            else voicemailSetupSub = sims.firstOrNull()?.subId ?: -1
        },
        // Tapping a call-log row asks first, instead of dialing immediately.
        onCallNumber = { number, name -> pendingCall = CallTarget(number, name) }
    )

    if (showVoicemailSimPicker) {
        VoicemailSimPickerDialog(
            sims = sims,
            context = context,
            onDismiss = { showVoicemailSimPicker = false },
            onPick = { subId ->
                showVoicemailSimPicker = false
                voicemailFor(subId)
            },
            onSetup = { subId ->
                showVoicemailSimPicker = false
                voicemailSetupSub = subId
            }
        )
    }

    pendingCall?.let { target ->
        ConfirmCallDialog(
            target = target,
            onDismiss = { pendingCall = null },
            onConfirm = {
                placeCall(context, target.number)
                pendingCall = null
            }
        )
    }

    if (showKeypad) {
        // System back button / gesture closes the keypad, same as its top-left arrow.
        BackHandler { showKeypad = false }
        KeypadScreen(
            onDismiss = { showKeypad = false },
            onCall = { number, subId ->
                placeCall(context, number, subId)
                showKeypad = false
            }
        )
    }

    voicemailSetupSub?.let { setupSub ->
        val simLabel = sims.firstOrNull { it.subId == setupSub }?.carrier
        VoicemailSetupDialog(
            simLabel = simLabel,
            subId = setupSub,
            carrierName = simLabel,
            initialNumber = Prefs.savedVoicemailNumber(context, setupSub).orEmpty(),
            initialPin = Prefs.voicemailPin(context, setupSub).orEmpty(),
            onDismiss = { voicemailSetupSub = null },
            onSave = { number, pin ->
                Prefs.setVoicemailNumber(context, number, setupSub)
                Prefs.setVoicemailPin(context, pin, setupSub)
                voicemailSetupSub = null
                // Saving from the voicemail flow? Place the call right away.
                callVoicemail(context, setupSub)
            }
        )
    }
}

private fun requiredPermissions(): Array<String> {
    val list = mutableListOf(
        Manifest.permission.CALL_PHONE,
        Manifest.permission.READ_CALL_LOG,
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.READ_PHONE_NUMBERS,
        Manifest.permission.READ_CONTACTS
    )
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        list += Manifest.permission.POST_NOTIFICATIONS
    }
    return list.toTypedArray()
}
