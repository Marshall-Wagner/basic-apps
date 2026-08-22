package dev.montb.basiccalendar.data

import android.content.Context
import org.json.JSONArray

/**
 * Tracks the most-recently picked time zones (newest first, capped) so the zone picker can
 * offer a quick "Recent" list, since the same handful of cities tend to get reused.
 */
object RecentZonesStore {
    private const val FILE = "zones_recent"
    private const val KEY = "recent"
    private const val MAX = 8

    private fun prefs(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun get(context: Context): List<String> {
        val raw = prefs(context).getString(KEY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getString(it) }
        }.getOrDefault(emptyList())
    }

    fun record(context: Context, zoneId: String) {
        val list = get(context).toMutableList()
        list.remove(zoneId)           // move to front if already present
        list.add(0, zoneId)
        prefs(context).edit().putString(KEY, JSONArray(list.take(MAX)).toString()).apply()
    }
}
