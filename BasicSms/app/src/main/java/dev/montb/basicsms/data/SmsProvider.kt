package dev.montb.basicsms.data

import android.content.ContentValues
import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import androidx.paging.PagingSource
import androidx.paging.PagingState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/**
 * Reads SMS straight from the SYSTEM provider (content://sms) instead of a private Room
 * DB. This is the robust default-SMS-app design: messages live in the OS-owned store, so
 * they survive an app uninstall/reinstall (the Room DB did NOT, that's why a reinstall
 * showed an empty inbox even though the texts were safe in the provider all along).
 *
 * Mirrors the shape the UI already expects (Conversation list + paged MessageEntity
 * thread + markRead) so the repository can swap to it with minimal churn.
 */
class SmsProvider(private val context: Context) {

    private val resolver get() = context.contentResolver

    // --- conversations list (one row per address, newest message + unread count) ---

    /** Emits the conversation list, re-querying whenever the SMS provider changes. */
    fun conversations(): Flow<List<Conversation>> = callbackFlow {
        fun push() { trySend(queryConversations()) }
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { push() }
        }
        resolver.registerContentObserver(Telephony.Sms.CONTENT_URI, true, observer)
        push() // initial load
        awaitClose { resolver.unregisterContentObserver(observer) }
    }.flowOn(Dispatchers.IO)

    private fun queryConversations(): List<Conversation> {
        // Walk all messages newest-first; keep the first (latest) per address and tally
        // unread incoming. Cheaper than per-address sub-queries and works on every ROM.
        val latest = LinkedHashMap<String, Conversation>()
        val unread = HashMap<String, Int>()
        resolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE,
                Telephony.Sms.READ, Telephony.Sms.TYPE),
            null, null, "${Telephony.Sms.DATE} DESC"
        )?.use { c ->
            val addrIdx = c.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val bodyIdx = c.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val dateIdx = c.getColumnIndexOrThrow(Telephony.Sms.DATE)
            val readIdx = c.getColumnIndexOrThrow(Telephony.Sms.READ)
            val typeIdx = c.getColumnIndexOrThrow(Telephony.Sms.TYPE)
            while (c.moveToNext()) {
                val address = c.getString(addrIdx)?.takeIf { it.isNotBlank() } ?: continue
                val incoming = c.getInt(typeIdx) == Telephony.Sms.MESSAGE_TYPE_INBOX
                if (incoming && c.getInt(readIdx) == 0) {
                    unread[address] = (unread[address] ?: 0) + 1
                }
                if (!latest.containsKey(address)) {
                    latest[address] = Conversation(
                        address = address,
                        lastBody = c.getString(bodyIdx) ?: "",
                        lastTimestamp = c.getLong(dateIdx),
                        unreadCount = 0 // filled in below
                    )
                }
            }
        }
        return latest.values.map { it.copy(unreadCount = unread[it.address] ?: 0) }
            .sortedByDescending { it.lastTimestamp }
    }

    // --- a single conversation thread, paged ---

    fun threadPagingSource(address: String): PagingSource<Int, MessageEntity> =
        ThreadPagingSource(context, address)

    private class ThreadPagingSource(
        private val context: Context,
        private val address: String
    ) : PagingSource<Int, MessageEntity>() {

        override suspend fun load(params: LoadParams<Int>): LoadResult<Int, MessageEntity> {
            val offset = params.key ?: 0
            val limit = params.loadSize
            return try {
                val rows = withContext(Dispatchers.IO) { query(offset, limit) }
                LoadResult.Page(
                    data = rows,
                    prevKey = if (offset == 0) null else (offset - limit).coerceAtLeast(0),
                    nextKey = if (rows.size < limit) null else offset + limit
                )
            } catch (e: Exception) {
                LoadResult.Error(e)
            }
        }

        override fun getRefreshKey(state: PagingState<Int, MessageEntity>): Int? = 0

        private fun query(offset: Int, limit: Int): List<MessageEntity> {
            val out = ArrayList<MessageEntity>(limit)
            context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY,
                    Telephony.Sms.DATE, Telephony.Sms.TYPE, Telephony.Sms.READ,
                    Telephony.Sms.SUBSCRIPTION_ID),
                "${Telephony.Sms.ADDRESS} = ?", arrayOf(address),
                "${Telephony.Sms.DATE} DESC LIMIT $limit OFFSET $offset"
            )?.use { c ->
                val idIdx = c.getColumnIndexOrThrow(Telephony.Sms._ID)
                val bodyIdx = c.getColumnIndexOrThrow(Telephony.Sms.BODY)
                val dateIdx = c.getColumnIndexOrThrow(Telephony.Sms.DATE)
                val typeIdx = c.getColumnIndexOrThrow(Telephony.Sms.TYPE)
                val readIdx = c.getColumnIndexOrThrow(Telephony.Sms.READ)
                val subIdx = c.getColumnIndexOrThrow(Telephony.Sms.SUBSCRIPTION_ID)
                while (c.moveToNext()) {
                    out += MessageEntity(
                        id = c.getLong(idIdx),
                        address = address,
                        body = c.getString(bodyIdx) ?: "",
                        timestamp = c.getLong(dateIdx),
                        incoming = c.getInt(typeIdx) == Telephony.Sms.MESSAGE_TYPE_INBOX,
                        read = c.getInt(readIdx) == 1,
                        subId = c.getInt(subIdx)
                    )
                }
            }
            return out
        }
    }

    // --- mark a conversation's incoming messages read (in the system provider) ---

    suspend fun markRead(address: String) = withContext(Dispatchers.IO) {
        runCatching {
            val values = ContentValues().apply { put(Telephony.Sms.READ, 1) }
            resolver.update(
                Telephony.Sms.CONTENT_URI, values,
                "${Telephony.Sms.ADDRESS} = ? AND ${Telephony.Sms.READ} = 0",
                arrayOf(address)
            )
        }
        Unit
    }
}
