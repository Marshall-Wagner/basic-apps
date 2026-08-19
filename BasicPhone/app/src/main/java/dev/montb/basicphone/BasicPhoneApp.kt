package dev.montb.basicphone

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import dev.montb.basicphone.incall.IncomingCallNotifier

class BasicPhoneApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // High-importance channel so incoming-call notifications can go full-screen.
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                IncomingCallNotifier.CHANNEL,
                "Incoming calls",
                NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "Shows the screen when a call comes in" }
        )
    }
}
