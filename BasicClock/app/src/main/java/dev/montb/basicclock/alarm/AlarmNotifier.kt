package dev.montb.basicclock.alarm

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.montb.basicclock.BasicClockApp
import dev.montb.basicclock.R
import dev.montb.basicclock.data.Alarm
import dev.montb.basicclock.ui.RingActivity

/**
 * Builds the ongoing alarm notification the [AlarmService] runs in the foreground with. Its
 * full-screen intent brings up [RingActivity] over the lock screen (when the app has been
 * granted full-screen-intent access); tapping it opens the same screen otherwise.
 */
object AlarmNotifier {
    const val NOTIF_ID = 42

    fun build(context: Context, alarm: Alarm?, label: String? = null): Notification {
        val ring = Intent(context, RingActivity::class.java)
            .putExtra(RingActivity.EXTRA_ID, alarm?.id)
            .putExtra(RingActivity.EXTRA_LABEL, label)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pending = PendingIntent.getActivity(
            context, alarm?.requestCode ?: 0, ring,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(context, BasicClockApp.CHANNEL_ALARM)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(label ?: alarm?.label?.takeIf { it.isNotBlank() } ?: "Alarm")
            .setContentText(
                when {
                    label != null -> "Time's up"
                    alarm != null -> "%02d:%02d  ·  %s".format(alarm.hour, alarm.minute, alarm.zoneId)
                    else -> ""
                }
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .setAutoCancel(false)
            .setFullScreenIntent(pending, true)
            .setContentIntent(pending)
            .build()
    }

    fun dismiss(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIF_ID)
    }
}
