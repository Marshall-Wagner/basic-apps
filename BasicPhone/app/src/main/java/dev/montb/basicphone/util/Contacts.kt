package dev.montb.basicphone.util

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract

/** Forward contact search: type a name OR digits, get matching contacts to call. */
object Contacts {

    data class Match(val name: String, val number: String)

    /**
     * Searches contacts by name or number. Uses Phone.CONTENT_FILTER_URI, which the
     * platform designed for dialer search (matches display name AND number). Run off
     * the main thread. Needs READ_CONTACTS.
     */
    fun search(context: Context, query: String, limit: Int = 20): List<Match> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        return try {
            val uri = Uri.withAppendedPath(
                ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI,
                Uri.encode(q)
            )
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            )
            val seen = HashSet<String>()
            val out = ArrayList<Match>()
            context.contentResolver.query(uri, projection, null, null, null)?.use { c ->
                val nameIdx = c.getColumnIndexOrThrow(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
                )
                val numIdx = c.getColumnIndexOrThrow(
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                )
                while (c.moveToNext() && out.size < limit) {
                    val name = c.getString(nameIdx)?.takeIf { it.isNotBlank() } ?: continue
                    val number = c.getString(numIdx)?.takeIf { it.isNotBlank() } ?: continue
                    // De-dupe the same number appearing under multiple raw contacts.
                    val key = number.filter { it.isDigit() || it == '+' }
                    if (seen.add(key)) out += Match(name, number)
                }
            }
            out
        } catch (e: SecurityException) {
            emptyList()
        }
    }
}
