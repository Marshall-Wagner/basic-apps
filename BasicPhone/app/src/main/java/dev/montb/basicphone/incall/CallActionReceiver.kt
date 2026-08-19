package dev.montb.basicphone.incall

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Handles Answer / Decline taps from the incoming-call notification. */
class CallActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_ANSWER -> {
                CallManager.answer()
                InCallActivity.start(context)
            }
            ACTION_DECLINE -> CallManager.hangup()
        }
        IncomingCallNotifier.cancel(context)
    }

    companion object {
        const val ACTION_ANSWER = "dev.montb.basicphone.ANSWER"
        const val ACTION_DECLINE = "dev.montb.basicphone.DECLINE"
    }
}
