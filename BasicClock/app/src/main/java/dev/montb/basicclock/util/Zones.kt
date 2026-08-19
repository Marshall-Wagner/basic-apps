package dev.montb.basicclock.util

import android.icu.util.TimeZone as IcuTimeZone
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/** Helpers for presenting time-zone regions in the picker and clock rows. */
object Zones {

    /** Region zone ids (contain "Area/City"), minus fixed-offset / legacy aliases. */
    val all: List<String> by lazy {
        ZoneId.getAvailableZoneIds()
            .filter { it.contains('/') && !it.startsWith("Etc/") && !it.startsWith("SystemV/") }
            .sorted()
    }

    /** The device's own zone id, used as the default for a new alarm. */
    fun device(): String = ZoneId.systemDefault().id

    /** "America/New_York" -> "New York". */
    fun cityLabel(zoneId: String): String = zoneId.substringAfterLast('/').replace('_', ' ')

    /** Country/region name for a zone (via ICU), or "" when it isn't country-specific. */
    fun country(zoneId: String): String {
        val region = runCatching { IcuTimeZone.getRegion(zoneId) }.getOrNull()
        if (region.isNullOrBlank() || region == "001") return ""   // 001 = no single country (e.g. UTC)
        return runCatching { Locale("", region).displayCountry }.getOrNull()
            ?.takeIf { it.isNotBlank() && it != region } ?: ""
    }

    /** "New York, United States", falls back to just the city when no country is known. */
    fun label(zoneId: String): String {
        val country = country(zoneId)
        return if (country.isNotEmpty()) "${cityLabel(zoneId)}, $country" else cityLabel(zoneId)
    }

    // Lowercased "zoneId city country" per zone so the picker can match by city OR country name.
    private val searchIndex: Map<String, String> by lazy {
        all.associateWith { "$it ${label(it)}".replace('/', ' ').replace('_', ' ').lowercase() }
    }

    fun matches(zoneId: String, query: String): Boolean {
        if (query.isBlank()) return true
        return searchIndex[zoneId]?.contains(query.lowercase()) == true
    }

    /** Current UTC offset like "GMT-04:00" (reflects DST at [now]). */
    fun offsetLabel(zoneId: String, now: Instant = Instant.now()): String {
        val z = runCatching { ZoneId.of(zoneId) }.getOrNull() ?: return ""
        val id = now.atZone(z).offset.id
        return "GMT" + if (id == "Z") "+00:00" else id
    }
}
