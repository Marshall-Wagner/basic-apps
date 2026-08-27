package dev.montb.basicphone.incall

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.telecom.CallAudioState
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import dev.montb.basicphone.ui.BasicPhoneTheme
import dev.montb.basicphone.util.Prefs
import kotlin.concurrent.thread

/** Full-screen in-call UI; shows over the lock screen for incoming/active calls. */
class InCallActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        setContent {
            BasicPhoneTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val state by CallManager.state.collectAsState()
                    InCallScreen(
                        state = state,
                        onToggleMute = { CallManager.toggleMute() },
                        onToggleSpeaker = {
                            val target = if (state.route == CallAudioState.ROUTE_SPEAKER) {
                                CallAudioState.ROUTE_EARPIECE
                            } else {
                                CallAudioState.ROUTE_SPEAKER
                            }
                            CallManager.route(target)
                        },
                        onEarpiece = { CallManager.route(CallAudioState.ROUTE_EARPIECE) },
                        onBluetooth = { CallManager.route(CallAudioState.ROUTE_BLUETOOTH) },
                        onDtmf = { digit -> CallManager.playDtmf(digit) },
                        onSendPin = {
                            // Send the saved PIN for this call's SIM as DTMF, off the
                            // main thread (sendDtmfSequence sleeps between tones).
                            val subId = state.accountId?.toIntOrNull() ?: -1
                            val pin = Prefs.voicemailPin(this@InCallActivity, subId)
                            if (pin != null) thread { CallManager.sendDtmfSequence("$pin#") }
                        },
                        onAnswer = { CallManager.answer() },
                        onHangup = { CallManager.hangup() },
                        onFinished = { finish() }
                    )
                }
            }
        }
    }

    companion object {
        fun start(context: Context) {
            context.startActivity(
                Intent(context, InCallActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
