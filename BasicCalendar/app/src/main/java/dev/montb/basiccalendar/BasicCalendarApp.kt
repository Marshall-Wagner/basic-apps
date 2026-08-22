package dev.montb.basiccalendar

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager

/** Application singleton, just creates the high-importance event notification channel. */
class BasicCalendarApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ALARM,
                getString(R.string.channel_alarm_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = getString(R.string.channel_alarm_desc)
                setBypassDnd(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                // The AlarmService plays the looping alarm sound + vibration itself (on the
                // alarm audio stream), so the notification channel stays silent, otherwise
                // both would sound at once.
                setSound(null, null)
                enableVibration(false)
            }
        )
    }

    companion object {
        const val CHANNEL_ALARM = "events"
    }
}
