package dev.montb.basicsms.util

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves a phone number to a contact display name via ContactsContract.PhoneLookup
 * (needs READ_CONTACTS). Results are cached in memory; "" marks a known miss so we
 * don't re-query numbers with no contact. Call this off the main thread.
 *
 * Mirrors BasicPhone's ContactNames so the two apps stay independent.
 */
object ContactNames {

    private val cache = ConcurrentHashMap<String, String>()

    /** Contact name for [number], or null if there's no matching contact. */
    fun lookup(context: Context, number: String): String? {
        if (number.isBlank()) return null
        cache[number]?.let { return it.ifEmpty { null } }
        val resolved = query(context, number)
        cache[number] = resolved ?: ""
        return resolved
    }

    /** Forget cached results so freshly-added contacts resolve on the next lookup. */
    fun invalidate() = cache.clear()

    private fun query(context: Context, number: String): String? {
        return try {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(number)
            )
            context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null, null, null
            )?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.takeIf { it.isNotBlank() } else null
            }
        } catch (e: SecurityException) {
            null
        }
    }
}
