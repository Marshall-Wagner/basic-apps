package dev.montb.basicsms.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import dev.montb.basicsms.BasicSmsApp
import dev.montb.basicsms.util.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The OS delivers SMS_DELIVER ONLY to the default SMS app, and it wakes this
 * manifest-registered receiver even if the app process was killed. That is what
 * makes background receipt reliable against aggressive task-killers.
 *
 * goAsync() gives us a short window to finish DB work off the main thread.
 */
class SmsDeliverReceiver : BroadcastReceiver() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (messages.isEmpty()) return

        val address = messages[0].displayOriginatingAddress ?: "Unknown"
        // Long texts arrive as multiple parts; concatenate the bodies.
        val body = messages.joinToString("") { it.displayMessageBody ?: "" }
        val timestamp = messages[0].timestampMillis
        // Which SIM received this. The de-facto extra key across OEMs is "subscription".
        val subId = intent.getIntExtra("subscription", -1)

        val pending = goAsync()
        val app = context.applicationContext as BasicSmsApp
        scope.launch {
            try {
                // Notify FIRST, and never let it depend on storage succeeding. For login
                // codes (e.g. PayPal OTP) the heads-up notification is the whole point, and
                // on this CN ROM the system-provider write can be rejected when the
                // default-SMS setting has drifted. If we stored first and that threw, the
                // notification was skipped and the user missed the code, the reported bug.
                Notifier.showIncoming(context, address, body)
                try {
                    app.repository.storeIncoming(address, body, timestamp, subId)
                } catch (e: Exception) {
                    // Keep the (already-shown) notification even if persisting fails.
                    Log.e("BasicSms", "storeIncoming failed; notification was still posted", e)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
