package dev.montb.basiccalendar.alarm

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.montb.basiccalendar.BasicCalendarApp
import dev.montb.basiccalendar.R
import dev.montb.basiccalendar.data.CalendarEvent
import dev.montb.basiccalendar.ui.RingActivity
import dev.montb.basiccalendar.util.Zones

/**
 * Builds the ongoing event notification the [AlarmService] runs in the foreground with. Its
 * full-screen intent brings up [RingActivity] over the lock screen (when the app has been
 * granted full-screen-intent access); tapping it opens the same screen otherwise.
 */
object AlarmNotifier {
    const val NOTIF_ID = 42

    fun build(context: Context, event: CalendarEvent?): Notification {
        val ring = Intent(context, RingActivity::class.java)
            .putExtra(RingActivity.EXTRA_ID, event?.id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pending = PendingIntent.getActivity(
            context, event?.requestCode ?: 0, ring,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(context, BasicCalendarApp.CHANNEL_ALARM)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(event?.label?.takeIf { it.isNotBlank() } ?: "Event")
            .setContentText(
                event?.let {
                    "%02d:%02d  ·  %s".format(it.hour, it.minute, Zones.cityLabel(it.zoneId))
                } ?: ""
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
