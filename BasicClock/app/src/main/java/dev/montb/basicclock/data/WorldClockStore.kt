package dev.montb.basicclock.data

import android.content.Context
import org.json.JSONArray

/** Persists the list of time-zone ids shown on the World Clock tab (order preserved). */
object WorldClockStore {
    private const val FILE = "worldclock"
    private const val KEY = "zones"

    private fun prefs(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun getAll(context: Context): List<String> {
        val raw = prefs(context).getString(KEY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getString(it) }
        }.getOrDefault(emptyList())
    }

    fun add(context: Context, zoneId: String): List<String> {
        val list = getAll(context).toMutableList()
        if (zoneId !in list) list.add(zoneId)
        save(context, list)
        return list
    }

    fun remove(context: Context, zoneId: String): List<String> {
        val list = getAll(context).filterNot { it == zoneId }
        save(context, list)
        return list
    }

    private fun save(context: Context, list: List<String>) {
        prefs(context).edit().putString(KEY, JSONArray(list).toString()).apply()
    }
}
