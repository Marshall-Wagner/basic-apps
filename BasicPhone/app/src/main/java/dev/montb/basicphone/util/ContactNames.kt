package dev.montb.basicphone.util

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves a phone number to a contact display name via ContactsContract.PhoneLookup
 * (needs READ_CONTACTS). Results are cached in memory; "" marks a known miss so we
 * don't re-query numbers with no contact. Call this off the main thread.
 */
object ContactNames {

    private val cache = ConcurrentHashMap<String, String>()

    fun lookup(context: Context, number: String): String? {
        if (number.isBlank()) return null
        cache[number]?.let { return it.ifEmpty { null } }
        val resolved = query(context, number)
        cache[number] = resolved ?: ""
        return resolved
    }

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
