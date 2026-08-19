package dev.montb.basicclock.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists alarms as a small JSON array in private SharedPreferences. No database, no
 * dependencies, a handful of alarms is tiny, so this keeps the app's footprint minimal.
 */
object AlarmStore {
    private const val FILE = "alarms"
    private const val KEY = "list"

    private fun prefs(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun getAll(context: Context): List<Alarm> {
        val raw = prefs(context).getString(KEY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    fun get(context: Context, id: String): Alarm? = getAll(context).firstOrNull { it.id == id }

    fun saveAll(context: Context, alarms: List<Alarm>) {
        val arr = JSONArray()
        alarms.forEach { arr.put(toJson(it)) }
        prefs(context).edit().putString(KEY, arr.toString()).apply()
    }

    /** Insert or replace by id; returns the updated list. */
    fun upsert(context: Context, alarm: Alarm): List<Alarm> {
        val list = getAll(context).toMutableList()
        val idx = list.indexOfFirst { it.id == alarm.id }
        if (idx >= 0) list[idx] = alarm else list.add(alarm)
        saveAll(context, list)
        return list
    }

    fun delete(context: Context, id: String): List<Alarm> {
        val list = getAll(context).filterNot { it.id == id }
        saveAll(context, list)
        return list
    }

    private fun toJson(a: Alarm) = JSONObject().apply {
        put("id", a.id)
        put("hour", a.hour)
        put("minute", a.minute)
        put("zoneId", a.zoneId)
        put("label", a.label)
        put("days", JSONArray(a.days.toList()))
        put("enabled", a.enabled)
        if (a.soundUri != null) put("soundUri", a.soundUri)
    }

    private fun fromJson(o: JSONObject): Alarm {
        val daysArr = o.optJSONArray("days") ?: JSONArray()
        val days = (0 until daysArr.length()).map { daysArr.getInt(it) }.toSet()
        return Alarm(
            id = o.getString("id"),
            hour = o.getInt("hour"),
            minute = o.getInt("minute"),
            zoneId = o.getString("zoneId"),
            label = o.optString("label", ""),
            days = days,
            enabled = o.optBoolean("enabled", true),
            soundUri = o.optString("soundUri", "").ifBlank { null }
        )
    }
}
