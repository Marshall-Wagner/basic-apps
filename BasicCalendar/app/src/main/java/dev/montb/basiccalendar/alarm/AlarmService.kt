package dev.montb.basiccalendar.alarm

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import dev.montb.basiccalendar.data.CalendarEvent
import dev.montb.basiccalendar.data.EventStore

/**
 * Foreground service that actually rings: it plays the looping alarm sound (on the alarm
 * audio stream) and vibrates, and runs in the foreground behind the full-screen event
 * notification. Because the *service*, not the activity, produces the sound and vibration,
 * the event is heard even when Android downgrades the full-screen intent to a heads-up
 * notification (which it does on 14+ unless full-screen-intent access is granted).
 */
class AlarmService : Service() {

    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val event = intent?.getStringExtra(EXTRA_ID)?.let { EventStore.get(this, it) }
        val notification = AlarmNotifier.build(this, event)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(AlarmNotifier.NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(AlarmNotifier.NOTIF_ID, notification)
        }

        startRinging(event)
        return START_STICKY
    }

    private fun startRinging(event: CalendarEvent?) {
        val uri: Uri = event?.soundUri?.let { Uri.parse(it) }
            ?: RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM)
            ?: Settings.System.DEFAULT_ALARM_ALERT_URI
        runCatching {
            player = MediaPlayer().apply {
                setDataSource(this@AlarmService, uri)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                isLooping = true
                prepare()
                start()
            }
        }
        @Suppress("DEPRECATION")
        vibrator = getSystemService(VIBRATOR_SERVICE) as? Vibrator
        // Repeat the on/off pattern from index 0 until cancelled.
        vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 700, 700), 0))
    }

    override fun onDestroy() {
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        vibrator?.cancel()
        vibrator = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "dev.montb.basiccalendar.action.STOP"
        const val EXTRA_ID = "event_id"

        fun start(context: Context, eventId: String) {
            val i = Intent(context, AlarmService::class.java).putExtra(EXTRA_ID, eventId)
            context.startForegroundService(i)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, AlarmService::class.java).setAction(ACTION_STOP))
        }
    }
}
