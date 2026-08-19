package dev.montb.basicphone.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

/**
 * "Look up this number", opens a web search for the number in the user's browser so
 * they can check it against reverse-lookup / spam-report sites. Privacy-respecting by
 * design: nothing is sent anywhere automatically; this only fires when the user taps,
 * and it just hands a search query to whatever browser they already use.
 */
object NumberLookup {

    fun lookup(context: Context, number: String) {
        val q = number.trim()
        if (q.isEmpty()) return
        // A plain web search for the number, surfaces spam-report sites, business
        // listings, etc. without us bundling or calling any specific lookup API. The user
        // picks the search engine (Google or the more privacy-respecting DuckDuckGo).
        val query = Uri.encode("who called from $q")
        val url = when (Prefs.searchEngine(context)) {
            Prefs.SearchEngine.DUCKDUCKGO -> "https://duckduckgo.com/?q=$query"
            Prefs.SearchEngine.GOOGLE -> "https://www.google.com/search?q=$query"
        }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "No browser available", Toast.LENGTH_SHORT).show()
        }
    }
}
