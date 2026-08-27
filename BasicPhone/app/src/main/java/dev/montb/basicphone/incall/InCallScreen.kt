package dev.montb.basicphone.incall

import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.Connection
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.montb.basicphone.util.Sims

@Composable
fun InCallScreen(
    state: CallUiState,
    onToggleMute: () -> Unit,
    onToggleSpeaker: () -> Unit,
    onEarpiece: () -> Unit,
    onBluetooth: () -> Unit,
    onDtmf: (Char) -> Unit,
    onSendPin: () -> Unit,
    onAnswer: () -> Unit,
    onHangup: () -> Unit,
    onFinished: () -> Unit
) {
    // Close the screen automatically once the call ends.
    LaunchedEffect(state.active, state.callState) {
        if (!state.active || state.callState == Call.STATE_DISCONNECTED) onFinished()
    }

    val context = LocalContext.current
    val carrier = remember(state.accountId) { Sims.carrierForAccount(context, state.accountId) }

    val ringing = state.callState == Call.STATE_RINGING
    val onSpeaker = state.route == CallAudioState.ROUTE_SPEAKER
    val onBt = state.route == CallAudioState.ROUTE_BLUETOOTH
    val onEar = state.route == CallAudioState.ROUTE_EARPIECE
    val btSupported = (state.supportedRouteMask and CallAudioState.ROUTE_BLUETOOTH) != 0
    val earSupported = (state.supportedRouteMask and CallAudioState.ROUTE_EARPIECE) != 0

    // In-call DTMF keypad visibility (for voicemail PINs / IVR menus).
    var showKeypad by remember { mutableStateOf(false) }
    // What's been entered, shown as feedback above the keypad.
    var dtmfEntry by remember { mutableStateOf("") }

    // System back closes the DTMF keypad (rather than leaving the call screen) while
    // it's open. When it's closed we don't intercept back, so you can leave the call UI.
    BackHandler(enabled = showKeypad) { showKeypad = false }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.size(64.dp))
        Text(
            state.displayName,
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.size(8.dp))
        Text(
            stateLabel(state.callState),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (carrier != null) {
            Spacer(Modifier.size(4.dp))
            Text(
                carrier,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
        }

        // STIR/SHAKEN caller-verification result (the real, accessible spam signal).
        val verify = when (state.verificationStatus) {
            Connection.VERIFICATION_STATUS_PASSED -> "✓ Verified caller" to Color(0xFF2E7D32)
            Connection.VERIFICATION_STATUS_FAILED -> "⚠ Unverified — possible spam" to Color(0xFFC62828)
            else -> null
        }
        if (verify != null) {
            Spacer(Modifier.size(4.dp))
            Text(verify.first, color = verify.second, style = MaterialTheme.typography.labelLarge)
        }

        // DTMF entry feedback (what you've typed during the call).
        if (dtmfEntry.isNotEmpty()) {
            Spacer(Modifier.size(8.dp))
            Text(dtmfEntry, style = MaterialTheme.typography.headlineSmall)
        }

        Spacer(Modifier.weight(1f))

        // In-call keypad: tap a key to send a DTMF tone on the live call.
        if (showKeypad && !ringing) {
            DtmfKeypad(
                onKey = { key ->
                    onDtmf(key)
                    dtmfEntry += key
                }
            )
            Spacer(Modifier.size(24.dp))
        }

        // Audio controls (hidden while ringing, you mute/route after answering).
        if (!ringing) {
            // Call controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                ControlButton(
                    icon = if (state.muted) Icons.Filled.MicOff else Icons.Filled.Mic,
                    label = "Mute",
                    selected = state.muted,
                    onClick = onToggleMute
                )
                ControlButton(
                    icon = Icons.Filled.Dialpad,
                    label = "Keypad",
                    selected = showKeypad,
                    onClick = { showKeypad = !showKeypad }
                )
                // Sends your saved voicemail PIN as touch-tones on demand, the reliable
                // way to enter a PIN (post-dial ",,,PIN#" auto-send is unreliable). Tap
                // once the voicemail greeting/prompt is done.
                ControlButton(
                    icon = Icons.Filled.Password,
                    label = "Send PIN",
                    selected = false,
                    onClick = onSendPin
                )
            }
            Spacer(Modifier.size(16.dp))
            // Audio route: earpiece / speaker / Bluetooth (tap the one you want)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                ControlButton(
                    icon = Icons.Filled.PhoneInTalk,
                    label = "Earpiece",
                    selected = onEar,
                    enabled = earSupported,
                    onClick = onEarpiece
                )
                ControlButton(
                    icon = Icons.AutoMirrored.Filled.VolumeUp,
                    label = "Speaker",
                    selected = onSpeaker,
                    onClick = onToggleSpeaker
                )
                ControlButton(
                    icon = Icons.Filled.Bluetooth,
                    label = "Bluetooth",
                    selected = onBt,
                    enabled = btSupported,
                    onClick = onBluetooth
                )
            }
            Spacer(Modifier.size(40.dp))
        }

        // Answer / reject / end
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (ringing) Arrangement.SpaceEvenly else Arrangement.Center
        ) {
            if (ringing) {
                RoundAction(
                    icon = Icons.Filled.Call,
                    background = Color(0xFF2E7D32),
                    contentDescription = "Answer",
                    onClick = onAnswer
                )
            }
            RoundAction(
                icon = Icons.Filled.CallEnd,
                background = Color(0xFFC62828),
                contentDescription = if (ringing) "Reject" else "End call",
                onClick = onHangup
            )
        }
        Spacer(Modifier.size(32.dp))
    }
}

/** 3x4 in-call DTMF keypad; each tap sends the touch-tone via [onKey]. */
@Composable
private fun DtmfKeypad(onKey: (Char) -> Unit) {
    val rows = listOf(
        listOf('1', '2', '3'),
        listOf('4', '5', '6'),
        listOf('7', '8', '9'),
        listOf('*', '0', '#')
    )
    Column(
        modifier = Modifier.widthIn(max = 320.dp).fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                row.forEach { key ->
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1.6f)
                            .clickable { onKey(key) }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(key.toString(), style = MaterialTheme.typography.headlineSmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ControlButton(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val container = when {
        !enabled -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        selected -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val content = if (selected && enabled) MaterialTheme.colorScheme.onPrimary
    else MaterialTheme.colorScheme.onSurfaceVariant

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(shape = CircleShape, color = container, modifier = Modifier.size(64.dp)) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxSize().clickable(enabled = enabled, onClick = onClick)
            ) {
                Icon(icon, contentDescription = label, tint = content, modifier = Modifier.size(28.dp))
            }
        }
        Spacer(Modifier.size(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun RoundAction(
    icon: ImageVector,
    background: Color,
    contentDescription: String,
    onClick: () -> Unit
) {
    Surface(shape = CircleShape, color = background, modifier = Modifier.size(72.dp)) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxSize().clickable(onClick = onClick)
        ) {
            Icon(
                icon,
                contentDescription = contentDescription,
                tint = Color.White,
                modifier = Modifier.size(32.dp)
            )
        }
    }
}

private fun stateLabel(callState: Int): String = when (callState) {
    Call.STATE_DIALING -> "Dialing…"
    Call.STATE_RINGING -> "Incoming call"
    Call.STATE_ACTIVE -> "On call"
    Call.STATE_HOLDING -> "On hold"
    Call.STATE_CONNECTING -> "Connecting…"
    Call.STATE_DISCONNECTING -> "Ending…"
    Call.STATE_DISCONNECTED -> "Call ended"
    else -> ""
}
