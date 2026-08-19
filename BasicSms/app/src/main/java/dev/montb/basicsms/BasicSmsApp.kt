package dev.montb.basicsms

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import dev.montb.basicsms.data.AppDatabase
import dev.montb.basicsms.data.SmsRepository

/**
 * Application singleton. Owns the database + repository so receivers/services and
 * the UI all share one instance.
 */
class BasicSmsApp : Application() {

    val database by lazy { AppDatabase.get(this) }
    val repository by lazy { SmsRepository(this, database.messageDao()) }

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_MESSAGES,
                "Incoming messages",
                NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "Notifies you when a new SMS arrives" }
        )
    }

    companion object {
        const val CHANNEL_MESSAGES = "messages"
    }
}
