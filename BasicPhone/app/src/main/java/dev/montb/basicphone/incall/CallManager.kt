package dev.montb.basicphone.incall

import android.os.Build
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.VideoProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** Observable snapshot of the current call for the in-call UI. */
data class CallUiState(
    val active: Boolean = false,
    val callState: Int = Call.STATE_NEW,
    val displayName: String = "",
    val muted: Boolean = false,
    val route: Int = CallAudioState.ROUTE_EARPIECE,
    val supportedRouteMask: Int = 0,
    val accountId: String? = null,       // which SIM the call is on (PhoneAccountHandle id)
    val verificationStatus: Int = -1     // STIR/SHAKEN result (Connection.VERIFICATION_STATUS_*)
)

/**
 * Bridges the [CallService] (system-owned) and the [InCallActivity] UI. Holds the live
 * [Call] + the [CallService] reference (for mute/audio routing) and a resolved contact
 * name (looked up off the main thread by the service).
 */
object CallManager {

    private val _state = MutableStateFlow(CallUiState())
    val state: StateFlow<CallUiState> = _state

    var service: CallService? = null
    private var call: Call? = null
    private var contactName: String? = null
    // Whether this call was already auto-routed to a connected Bluetooth device (e.g. the
    // car). Done once per call so a later manual route change (earpiece/speaker) sticks.
    private var autoRoutedBt = false

    private val callback = object : Call.Callback() {
        override fun onStateChanged(c: Call, newState: Int) = refresh()
        override fun onDetailsChanged(c: Call, details: Call.Details) = refresh()
    }

    fun setCall(newCall: Call?) {
        call?.unregisterCallback(callback)
        call = newCall
        autoRoutedBt = false
        if (newCall == null) contactName = null
        newCall?.registerCallback(callback)
        refresh()
    }

    /** Contact name resolved off the main thread by CallService. */
    fun setContactName(name: String?) {
        contactName = name
        refresh()
    }

    fun onAudioChanged(audio: CallAudioState) {
        _state.update {
            it.copy(
                muted = audio.isMuted,
                route = audio.route,
                supportedRouteMask = audio.supportedRouteMask
            )
        }
        // Auto-route to a connected Bluetooth device (e.g. the car) the first time it becomes
        // available, so a call placed or answered in the car goes to the car stereo instead of
        // the phone earpiece. Only once per call, after that, a manual route change sticks.
        // (No-op if the system already routed to Bluetooth, since route == ROUTE_BLUETOOTH.)
        if (!autoRoutedBt &&
            (audio.supportedRouteMask and CallAudioState.ROUTE_BLUETOOTH) != 0 &&
            audio.route != CallAudioState.ROUTE_BLUETOOTH
        ) {
            autoRoutedBt = true
            route(CallAudioState.ROUTE_BLUETOOTH)
        }
    }

    fun answer() {
        call?.answer(VideoProfile.STATE_AUDIO_ONLY)
    }

    fun hangup() {
        val c = call ?: return
        @Suppress("DEPRECATION")
        if (c.state == Call.STATE_RINGING) c.reject(false, null) else c.disconnect()
    }

    fun toggleMute() {
        service?.setMuted(!_state.value.muted)
    }

    @Suppress("DEPRECATION")
    fun route(route: Int) {
        service?.setAudioRoute(route)
    }

    /**
     * Sends a DTMF touch-tone for the given key (0-9, *, #) on the live call. Used for
     * voicemail PINs and "press 1 for…" IVR menus. We play then immediately stop the
     * tone, which is the standard tap behavior.
     */
    fun playDtmf(digit: Char) {
        val c = call ?: return
        c.playDtmfTone(digit)
        c.stopDtmfTone()
    }

    /**
     * Sends a whole DTMF string (e.g. a saved voicemail PIN like "1234#") on demand,
     * the reliable manual alternative to post-dial ",,,PIN#" auto-send, which Telecom
     * doesn't always replay. The user taps "Send PIN" once the greeting is done, so
     * timing is never a problem. A short gap between tones keeps them distinct.
     */
    fun sendDtmfSequence(digits: String) {
        val c = call ?: return
        for (ch in digits) {
            if (ch == ',') { Thread.sleep(300); continue }  // optional pause in the string
            c.playDtmfTone(ch)
            c.stopDtmfTone()
            Thread.sleep(120)
        }
    }

    @Suppress("DEPRECATION")
    private fun refresh() {
        val c = call
        if (c == null) {
            _state.update { it.copy(active = false, callState = Call.STATE_DISCONNECTED) }
            return
        }
        val details = c.details
        val number = details.handle?.schemeSpecificPart
        val name = contactName?.takeIf { it.isNotBlank() }
            ?: details.callerDisplayName?.takeIf { it.isNotBlank() }   // carrier label (e.g. "Scam Likely") arrives here
            ?: number
            ?: "Unknown"
        val accountId = details.accountHandle?.id
        val verification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            details.callerNumberVerificationStatus
        } else -1
        _state.update {
            it.copy(
                active = true,
                callState = c.state,
                displayName = name,
                accountId = accountId,
                verificationStatus = verification
            )
        }
    }
}
