package dev.montb.basicclock.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Woken by AlarmManager when the countdown reaches zero. Rings via the shared [AlarmService]
 * (labelled "Timer") and clears the stored running state so the UI returns to idle.
 */
class TimerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FIRE) return
        AlarmService.startTimer(context, "Timer")
        TimerScheduler.onFired(context)
    }

    companion object {
        const val ACTION_FIRE = "dev.montb.basicclock.action.TIMER_FIRE"
    }
}
