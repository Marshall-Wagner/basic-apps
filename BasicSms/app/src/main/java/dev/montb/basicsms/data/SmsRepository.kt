package dev.montb.basicsms.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.Telephony
import android.util.Log
import android.telephony.SmsManager
import dev.montb.basicsms.util.Notifier
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import kotlinx.coroutines.flow.Flow

/**
 * Single source of truth for sending/receiving/storing SMS, now SIM-aware: each
 * message records the subscription id it arrived on / was sent from, and sending
 * uses the chosen SIM. All DB work is suspend (off the main thread).
 */
class SmsRepository(
    private val context: Context,
    private val dao: MessageDao
) {

    // Read the live UI straight from the SYSTEM provider so messages survive an app
    // uninstall/reinstall (the Room DB does not). Room is kept only for the backup-import
    // feature below.
    private val provider = SmsProvider(context)

    fun conversations(): Flow<List<Conversation>> = provider.conversations()

    /** Import SMS from an "SMS Import / Export" backup .zip. Still writes to Room AND the
     *  system provider via storeIncoming-style paths in the importer. */
    suspend fun importBackup(zipUri: Uri): SmsImporter.Result =
        SmsImporter.importFromZip(context, zipUri, dao)

    fun messagesFor(address: String): Flow<PagingData<MessageEntity>> =
        Pager(PagingConfig(pageSize = 50, enablePlaceholders = false)) {
            provider.threadPagingSource(address)
        }.flow

    suspend fun markRead(address: String) {
        provider.markRead(address)
        // Reading the thread in-app should also clear its notification from the shade.
        Notifier.cancel(context, address)
    }

    /** Persist a received message (called from the SMS_DELIVER receiver). */
    suspend fun storeIncoming(address: String, body: String, timestamp: Long, subId: Int) {
        dao.insert(
            MessageEntity(
                address = address, body = body, timestamp = timestamp,
                incoming = true, read = false, subId = subId
            )
        )
        writeToSystemProvider(address, body, timestamp, incoming = true, subId = subId)
    }

    /** Send from the chosen SIM (subId), then store. subId < 0 = default SIM. */
    suspend fun sendMessage(address: String, body: String, subId: Int) {
        val sms = smsManager(subId)
        val parts = sms.divideMessage(body)
        if (parts.size > 1) {
            sms.sendMultipartTextMessage(address, null, parts, null, null)
        } else {
            sms.sendTextMessage(address, null, body, null, null)
        }
        val now = System.currentTimeMillis()
        dao.insert(
            MessageEntity(
                address = address, body = body, timestamp = now,
                incoming = false, read = true, subId = subId
            )
        )
        writeToSystemProvider(address, body, now, incoming = false, subId = subId)
    }

    private fun smsManager(subId: Int): SmsManager =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val base = context.getSystemService(SmsManager::class.java)
            if (subId >= 0) base.createForSubscriptionId(subId) else base
        } else {
            @Suppress("DEPRECATION")
            if (subId >= 0) SmsManager.getSmsManagerForSubscriptionId(subId)
            else SmsManager.getDefault()
        }

    private fun writeToSystemProvider(
        address: String, body: String, timestamp: Long, incoming: Boolean, subId: Int
    ) {
        val values = ContentValues().apply {
            put(Telephony.Sms.ADDRESS, address)
            put(Telephony.Sms.BODY, body)
            put(Telephony.Sms.DATE, timestamp)
            put(Telephony.Sms.READ, if (incoming) 0 else 1)
            put(
                Telephony.Sms.TYPE,
                if (incoming) Telephony.Sms.MESSAGE_TYPE_INBOX
                else Telephony.Sms.MESSAGE_TYPE_SENT
            )
            if (subId >= 0) put(Telephony.Sms.SUBSCRIPTION_ID, subId)
        }
        // Writing to the SYSTEM provider is what makes messages survive an app
        // uninstall/reinstall (the Room DB does NOT, it's deleted with the app). A
        // failure here used to be silently swallowed, which hid that received messages
        // weren't being persisted system-wide. Now we log it loudly.
        runCatching {
            val uri = if (incoming) Telephony.Sms.Inbox.CONTENT_URI else Telephony.Sms.Sent.CONTENT_URI
            context.contentResolver.insert(uri, values)
        }.onSuccess { resultUri ->
            if (resultUri == null) {
                Log.e("BasicSms", "System SMS provider insert returned null (not persisted): $address")
            }
        }.onFailure { e ->
            Log.e("BasicSms", "Failed to write SMS to system provider", e)
        }
    }
}
