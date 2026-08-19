package dev.montb.basicsms.util

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.montb.basicsms.BasicSmsApp
import dev.montb.basicsms.R
import dev.montb.basicsms.ui.MainActivity

object Notifier {

    // A single notification id; conversations are told apart by the notification TAG instead
    // (see [key]). Tags compare by string equality, so unlike address.hashCode() two senders
    // can never collide onto the same slot, and cancel() always matches the right one.
    private const val NOTIF_ID = 1

    /**
     * Canonical per-sender key used as the notification tag. It strips spacing/punctuation so
     * the post-time address (the raw SMS `displayOriginatingAddress`, e.g. "+1 (555) 123-4567")
     * and the cancel-time address (what the system provider stored, which this ROM may reformat
     * to "+15551234567") normalize to the same string, so reading a thread reliably clears its
     * notification. Digits, '+', and alphanumeric sender IDs (e.g. "PayPal") are preserved.
     */
    private fun key(address: String): String =
        address.filterNot { it.isWhitespace() || it == '(' || it == ')' || it == '-' || it == '.' }
            .ifEmpty { address }

    fun showIncoming(context: Context, address: String, body: String) {
        // POST_NOTIFICATIONS is a runtime permission only on Android 13+. On older versions
        // (e.g. the ROG 5's Android 11) it isn't a recognized permission, so checkSelfPermission
        // returns DENIED, without this SDK guard, that would wrongly suppress EVERY SMS
        // notification on Android 11/12, where notifications need no runtime grant at all.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return

        val key = key(address)
        val openIntent = Intent(context, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_ADDRESS, address)
            // Distinct data per sender so each conversation gets its own PendingIntent, even if
            // request codes collide, PendingIntent equality compares data (not extras), so one
            // sender's tap target can't overwrite another's.
            data = Uri.parse("basicsms:thread/" + Uri.encode(key))
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            context, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, BasicSmsApp.CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(address)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()

        NotificationManagerCompat.from(context).notify(key, NOTIF_ID, notification)
    }

    /** Dismiss the notification for a conversation once its messages have been read in-app.
     *  Without this, reading a thread inside the app leaves its notification stuck in the shade
     *  (setAutoCancel only fires when the notification itself is tapped). Uses the same
     *  normalized [key] tag as [showIncoming] so it matches even if the address was reformatted. */
    fun cancel(context: Context, address: String) {
        NotificationManagerCompat.from(context).cancel(key(address), NOTIF_ID)
    }
}
