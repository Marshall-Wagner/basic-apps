package dev.montb.basicphone.incall

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import dev.montb.basicphone.util.ContactNames
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The system binds this whenever a call exists (because we're the default phone app).
 * Forwards call + audio events to [CallManager], resolves the caller's contact name,
 * and surfaces the in-call UI:
 *  - ringing  -> a full-screen-intent notification (reliable on aggressive ROMs)
 *  - active/dialing -> launch the in-call screen directly
 */
class CallService : InCallService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val callback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) = handleState(state)
    }

    // When the screen wakes (or the user unlocks) during a live call, re-show the
    // in-call screen, so after the display times out you don't land on the lock
    // screen / home with the call hidden. With a passcode it appears over the lock
    // screen (showWhenLocked); you unlock to interact.
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val call = CallManager.state.value
            if (call.active && call.callState != Call.STATE_DISCONNECTED) {
                InCallActivity.start(this@CallService)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        CallManager.service = this
        registerReceiver(
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT) // fired after unlocking
            }
        )
    }

    override fun onDestroy() {
        CallManager.service = null
        runCatching { unregisterReceiver(screenReceiver) }
        scope.cancel()
        super.onDestroy()
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        CallManager.setCall(call)
        call.registerCallback(callback)
        resolveContactName(call)
        @Suppress("DEPRECATION")
        handleState(call.state)
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        call.unregisterCallback(callback)
        CallManager.setCall(null)
        IncomingCallNotifier.cancel(this)
    }

    override fun onCallAudioStateChanged(audioState: CallAudioState) {
        super.onCallAudioStateChanged(audioState)
        CallManager.onAudioChanged(audioState)
    }

    private fun resolveContactName(call: Call) {
        val number = call.details.handle?.schemeSpecificPart ?: return
        scope.launch {
            val name = withContext(Dispatchers.IO) {
                ContactNames.lookup(applicationContext, number)
            }
            if (name != null) CallManager.setContactName(name)
        }
    }

    private fun handleState(state: Int) {
        when (state) {
            Call.STATE_RINGING -> IncomingCallNotifier.show(this)
            Call.STATE_DIALING, Call.STATE_CONNECTING, Call.STATE_ACTIVE -> {
                IncomingCallNotifier.cancel(this)
                InCallActivity.start(this)
            }
            Call.STATE_DISCONNECTED -> IncomingCallNotifier.cancel(this)
        }
    }
}
