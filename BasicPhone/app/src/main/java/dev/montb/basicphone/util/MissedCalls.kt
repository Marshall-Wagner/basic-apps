package dev.montb.basicphone.util

import android.content.Context
import android.telecom.TelecomManager

/**
 * Clears the platform's "N missed calls" notification and count. That notification is owned
 * by Telecom, NOT by us, as the default dialer we're responsible for telling Telecom the
 * user has now seen the missed calls. Without this the count never resets and just keeps
 * climbing (the reported "26 missed calls" that won't clear).
 *
 * cancelMissedCallsNotification() also resets the CallLog "new" flags at the source (the
 * platform does that write with its own privileges), so nothing is left to rebuild the
 * count and we need no WRITE_CALL_LOG / MODIFY_PHONE_STATE permission of our own.
 *
 * No-op (SecurityException swallowed) if we aren't currently the default dialer.
 */
object MissedCalls {
    fun clear(context: Context) {
        runCatching {
            context.getSystemService(TelecomManager::class.java)
                ?.cancelMissedCallsNotification()
        }
    }
}
