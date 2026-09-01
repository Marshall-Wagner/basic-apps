package dev.montb.basiccalendar.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists events as a small JSON array in private SharedPreferences. No database, no
 * dependencies, a handful of events is tiny, so this keeps the app's footprint minimal.
 */
object EventStore {
    private const val FILE = "events"
    private const val KEY = "list"

    private fun prefs(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun getAll(context: Context): List<CalendarEvent> {
        val raw = prefs(context).getString(KEY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    fun get(context: Context, id: String): CalendarEvent? =
        getAll(context).firstOrNull { it.id == id }

    fun saveAll(context: Context, events: List<CalendarEvent>) {
        val arr = JSONArray()
        events.forEach { arr.put(toJson(it)) }
        prefs(context).edit().putString(KEY, arr.toString()).apply()
    }

    /** Insert or replace by id; returns the updated list. */
    fun upsert(context: Context, event: CalendarEvent): List<CalendarEvent> {
        val list = getAll(context).toMutableList()
        val idx = list.indexOfFirst { it.id == event.id }
        if (idx >= 0) list[idx] = event else list.add(event)
        saveAll(context, list)
        return list
    }

    fun delete(context: Context, id: String): List<CalendarEvent> {
        val list = getAll(context).filterNot { it.id == id }
        saveAll(context, list)
        return list
    }

    private fun toJson(e: CalendarEvent) = JSONObject().apply {
        put("id", e.id)
        put("year", e.year)
        put("month", e.month)
        put("day", e.day)
        put("hour", e.hour)
        put("minute", e.minute)
        put("zoneId", e.zoneId)
        put("label", e.label)
        put("repeat", e.repeat.name)
        put("leadMinutes", e.leadMinutes)
        put("enabled", e.enabled)
        if (e.soundUri != null) put("soundUri", e.soundUri)
    }

    private fun fromJson(o: JSONObject): CalendarEvent = CalendarEvent(
        id = o.getString("id"),
        year = o.getInt("year"),
        month = o.getInt("month"),
        day = o.getInt("day"),
        hour = o.getInt("hour"),
        minute = o.getInt("minute"),
        zoneId = o.getString("zoneId"),
        label = o.optString("label", ""),
        repeat = runCatching { Repeat.valueOf(o.optString("repeat", "NONE")) }
            .getOrDefault(Repeat.NONE),
        leadMinutes = o.optInt("leadMinutes", 0),
        enabled = o.optBoolean("enabled", true),
        soundUri = o.optString("soundUri", "").ifBlank { null }
    )
}
