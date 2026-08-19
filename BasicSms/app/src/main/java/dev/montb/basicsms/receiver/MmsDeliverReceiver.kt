package dev.montb.basicsms.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Required for default-SMS-app eligibility. Full MMS download (fetching the message
 * from the carrier MMSC over a dedicated APN, parsing PDUs, decoding attachments) is
 * a large subsystem and is intentionally out of scope for a "basic SMS" app.
 *
 * This receiver exists so the app qualifies as the default SMS app; it currently
 * just acknowledges the WAP push. Plain text SMS is fully handled.
 */
class MmsDeliverReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.i("MmsDeliverReceiver", "WAP push received (MMS body download not implemented)")
    }
}
