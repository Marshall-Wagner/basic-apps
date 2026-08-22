package dev.montb.basicphone.data

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.CallLog
import androidx.paging.PagingSource
import androidx.paging.PagingState
import dev.montb.basicphone.util.ContactNames
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Pages the system call-log provider with LIMIT/OFFSET so we never load the whole
 * history at once, the core fix for the stock dialer's scroll stutter. Also reads
 * PHONE_ACCOUNT_ID so each row can show which SIM/carrier it used.
 */
class CallLogPagingSource(private val context: Context) : PagingSource<Int, CallLogEntry>() {

    // Refresh the list live when the system call log changes (a call ends, a row is added or
    // deleted). Without this, new calls only appear after the app is reopened. We invalidate()
    // on change; Paging then swaps in a fresh PagingSource that registers its own observer.
    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = invalidate()
    }

    init {
        // Registering an observer on the call-log provider opens it, which needs READ_CALL_LOG.
        // On a fresh install that isn't the default dialer yet, that permission is denied and this
        // would throw SecurityException and crash on launch (One UI surfaces it on the main thread),
        // so guard it. Once the permission is granted the list refreshes, and the fresh source then
        // registers its observer successfully. The load() query is already SecurityException-safe.
        runCatching {
            context.contentResolver.registerContentObserver(CallLog.Calls.CONTENT_URI, true, observer)
            registerInvalidatedCallback { context.contentResolver.unregisterContentObserver(observer) }
        }
    }

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, CallLogEntry> {
        val offset = params.key ?: 0
        val limit = params.loadSize
        return try {
            val rows = withContext(Dispatchers.IO) { query(offset, limit) }
            LoadResult.Page(
                data = rows,
                prevKey = if (offset == 0) null else (offset - limit).coerceAtLeast(0),
                nextKey = if (rows.size < limit) null else offset + limit
            )
        } catch (e: SecurityException) {
            LoadResult.Error(e)
        }
    }

    override fun getRefreshKey(state: PagingState<Int, CallLogEntry>): Int? = 0

    private fun query(offset: Int, limit: Int): List<CallLogEntry> {
        val projection = arrayOf(
            CallLog.Calls._ID,
            CallLog.Calls.NUMBER,
            CallLog.Calls.CACHED_NAME,
            CallLog.Calls.TYPE,
            CallLog.Calls.DATE,
            CallLog.Calls.DURATION,
            CallLog.Calls.PHONE_ACCOUNT_ID,
            CallLog.Calls.NUMBER_PRESENTATION
        )
        val sortOrder = "${CallLog.Calls.DATE} DESC LIMIT $limit OFFSET $offset"

        val result = ArrayList<CallLogEntry>(limit)
        context.contentResolver.query(
            CallLog.Calls.CONTENT_URI, projection, null, null, sortOrder
        )?.use { c ->
            val idIdx = c.getColumnIndexOrThrow(CallLog.Calls._ID)
            val numIdx = c.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
            val nameIdx = c.getColumnIndexOrThrow(CallLog.Calls.CACHED_NAME)
            val typeIdx = c.getColumnIndexOrThrow(CallLog.Calls.TYPE)
            val dateIdx = c.getColumnIndexOrThrow(CallLog.Calls.DATE)
            val durIdx = c.getColumnIndexOrThrow(CallLog.Calls.DURATION)
            val acctIdx = c.getColumnIndexOrThrow(CallLog.Calls.PHONE_ACCOUNT_ID)
            val presIdx = c.getColumnIndexOrThrow(CallLog.Calls.NUMBER_PRESENTATION)
            while (c.moveToNext()) {
                val number = c.getString(numIdx) ?: ""
                // Prefer the log's cached name; otherwise look up the contact (cached,
                // and we're already on Dispatchers.IO here).
                val cachedName = c.getString(nameIdx)?.takeIf { it.isNotBlank() }
                result += CallLogEntry(
                    id = c.getLong(idIdx),
                    number = number,
                    name = cachedName ?: ContactNames.lookup(context, number),
                    type = c.getInt(typeIdx),
                    date = c.getLong(dateIdx),
                    durationSec = c.getLong(durIdx),
                    accountId = c.getString(acctIdx),
                    presentation = c.getInt(presIdx)
                )
            }
        }
        return applySpamHints(result)
    }

    /**
     * Offline spam heuristics, no online database, no privacy cost:
     *  - WITHHELD: caller ID hidden/unknown/payphone (a stored, authoritative signal).
     *  - REPEATED: an un-named number that appears 3+ times within this page (the
     *    classic robo/call-bomb pattern). Contacts are never flagged.
     * WITHHELD takes priority over REPEATED when both apply.
     */
    private fun applySpamHints(rows: List<CallLogEntry>): List<CallLogEntry> {
        val counts = rows.filter { it.name == null && it.number.isNotBlank() }
            .groupingBy { it.number }.eachCount()
        return rows.map { e ->
            val hint = when {
                e.presentation != CallLog.Calls.PRESENTATION_ALLOWED -> SpamHint.WITHHELD
                e.name == null && (counts[e.number] ?: 0) >= 3 -> SpamHint.REPEATED
                else -> SpamHint.NONE
            }
            if (hint == e.spamHint) e else e.copy(spamHint = hint)
        }
    }
}
