package dev.montb.basiccontacts.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

/**
 * Hand-off intents: dialing and texting are delegated to whatever the system's default
 * dialer / SMS app is (your BasicPhone / BasicSms). We never place the call ourselves.
 */
object Actions {

    /** ACTION_DIAL shows the number in the dialer (no CALL_PHONE permission needed). */
    fun dial(context: Context, number: String) {
        launch(context, Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(number)}")))
    }

    /** Open the SMS composer to this number in the default messaging app. */
    fun text(context: Context, number: String) {
        launch(context, Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(number)}")))
    }

    /** Open an email composer for this address. */
    fun email(context: Context, address: String) {
        launch(context, Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${Uri.encode(address)}")))
    }

    private fun launch(context: Context, intent: Intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "No app available for this action", Toast.LENGTH_SHORT).show()
        }
    }
}
