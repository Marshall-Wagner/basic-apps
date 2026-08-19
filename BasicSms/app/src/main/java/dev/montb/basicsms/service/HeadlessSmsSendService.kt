package dev.montb.basicsms.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.telephony.TelephonyManager
import dev.montb.basicsms.BasicSmsApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Handles "respond via message" quick replies the system may send (e.g. answering a
 * call with a text). Required for default-SMS-app eligibility.
 */
class HeadlessSmsSendService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == TelephonyManager.ACTION_RESPOND_VIA_MESSAGE) {
            val message = intent.getStringExtra(Intent.EXTRA_TEXT)
            val recipient = intent.data?.schemeSpecificPart
                ?.split(";", ",")?.firstOrNull()?.trim()
            if (!message.isNullOrEmpty() && !recipient.isNullOrEmpty()) {
                val app = applicationContext as BasicSmsApp
                // Quick-reply (e.g. reject-call-with-text): send on the default SIM.
                scope.launch { app.repository.sendMessage(recipient, message, subId = -1) }
            }
        }
        stopSelf(startId)
        return START_NOT_STICKY
    }
}
