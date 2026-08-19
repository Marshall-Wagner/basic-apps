package dev.montb.basicphone.incall

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import dev.montb.basicphone.R

/**
 * Posts a high-priority CallStyle notification with a full-screen intent for incoming
 * calls. The OS uses the full-screen intent to launch the in-call screen itself,
 * which bypasses background-activity-launch blocking on aggressive ROMs, and falls
 * back to a heads-up banner with Answer/Decline if full-screen is suppressed.
 */
object IncomingCallNotifier {

    const val CHANNEL = "incoming_calls"
    private const val NOTIF_ID = 1001

    fun show(context: Context) {
        // Without POST_NOTIFICATIONS (Android 13+) we can't post; best-effort direct launch.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            InCallActivity.start(context)
            return
        }

        val name = CallManager.state.value.displayName.ifBlank { "Incoming call" }
        val caller = Person.Builder().setName(name).build()

        val fullScreen = PendingIntent.getActivity(
            context, 0,
            Intent(context, InCallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val answer = action(context, CallActionReceiver.ACTION_ANSWER, requestCode = 1)
        val decline = action(context, CallActionReceiver.ACTION_DECLINE, requestCode = 2)

        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(name)
            .setContentText("Incoming call")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setFullScreenIntent(fullScreen, true)
            .setStyle(NotificationCompat.CallStyle.forIncomingCall(caller, decline, answer))
            .build()

        NotificationManagerCompat.from(context).notify(NOTIF_ID, notification)
    }

    fun cancel(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIF_ID)
    }

    private fun action(context: Context, actionName: String, requestCode: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context, requestCode,
            Intent(context, CallActionReceiver::class.java).setAction(actionName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
}
