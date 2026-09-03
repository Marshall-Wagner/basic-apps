package dev.montb.basicphone.util

import android.content.ContentValues
import android.content.Context
import android.provider.BaseColumns
import android.provider.BlockedNumberContract
import android.provider.BlockedNumberContract.BlockedNumbers

/**
 * Thin wrapper over the system BlockedNumberProvider. Reads and writes require the default-dialer
 * (or default-SMS) role, which BasicPhone holds; every call is guarded so a ROM that denies access
 * degrades to a no-op instead of crashing.
 */
object Blocking {

    data class Entry(val id: Long, val number: String)

    fun canBlock(context: Context): Boolean =
        runCatching { BlockedNumberContract.canCurrentUserBlockNumbers(context) }.getOrDefault(false)

    /** Add [number] to the block list; true on success (inserting an already-blocked number is fine). */
    fun block(context: Context, number: String): Boolean = runCatching {
        val values = ContentValues().apply { put(BlockedNumbers.COLUMN_ORIGINAL_NUMBER, number) }
        context.contentResolver.insert(BlockedNumbers.CONTENT_URI, values) != null
    }.getOrDefault(false)

    /** Remove [number] from the block list; true if a blocked entry was actually removed. */
    fun unblock(context: Context, number: String): Boolean =
        runCatching { BlockedNumberContract.unblock(context, number) > 0 }.getOrDefault(false)

    /** Every currently-blocked number, newest first as the provider returns them. */
    fun list(context: Context): List<Entry> = runCatching {
        val out = ArrayList<Entry>()
        context.contentResolver.query(
            BlockedNumbers.CONTENT_URI,
            arrayOf(BaseColumns._ID, BlockedNumbers.COLUMN_ORIGINAL_NUMBER),
            null, null, null
        )?.use { c ->
            val idIdx = c.getColumnIndexOrThrow(BaseColumns._ID)
            val numIdx = c.getColumnIndexOrThrow(BlockedNumbers.COLUMN_ORIGINAL_NUMBER)
            while (c.moveToNext()) out.add(Entry(c.getLong(idIdx), c.getString(numIdx).orEmpty()))
        }
        out
    }.getOrDefault(emptyList())
}
