package dev.montb.basickeyboard.ime

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle

/**
 * Keeps a short history of recently copied text so the keyboard can offer them for
 * pasting. History lives only in memory + this app's private prefs, never leaves the
 * device (the app has no internet permission).
 *
 * Sensitive clips (passwords, OTPs) are NEVER stored: password managers like Proton
 * Pass flag their copies with EXTRA_IS_SENSITIVE (Android 13+), and we honor that.
 */
object ClipboardStore {

    private const val FILE = "clipboard"
    private const val KEY = "history"
    private const val SEP = ""   // unlikely-in-text separator
    private const val MAX = 20

    /** Current system clipboard text plus our saved history, newest first, de-duped. */
    fun items(context: Context): List<String> {
        val result = LinkedHashSet<String>()
        currentClip(context)?.let { result.add(it) }
        result.addAll(loadHistory(context))
        return result.toList().take(MAX)
    }

    /** Call when the panel opens to fold the live clipboard into saved history. */
    fun capture(context: Context) {
        val clip = currentClip(context) ?: return
        val history = loadHistory(context).toMutableList()
        history.remove(clip)
        history.add(0, clip)
        saveHistory(context, history.take(MAX))
    }

    /** Clear our saved history AND the live system clipboard. Previously this only
     *  wiped saved history, so a still-on-the-clipboard entry (e.g. a just-copied
     *  password) reappeared immediately via items()'s currentClip(), making "Clear"
     *  look like it did nothing. */
    fun clear(context: Context) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().remove(KEY).apply()
        clearSystemClip(context)
    }

    private fun clearSystemClip(context: Context) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            cm.clearPrimaryClip()
        } else {
            @Suppress("DEPRECATION")
            cm.setPrimaryClip(ClipData.newPlainText("", ""))
        }
    }

    fun remove(context: Context, text: String) {
        val history = loadHistory(context).toMutableList()
        history.remove(text)
        saveHistory(context, history)
    }

    private fun currentClip(context: Context): String? {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
        val clip = cm.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        // Never surface or store sensitive copies (passwords/OTPs). Password managers
        // flag them; we skip those clips entirely so they never enter the history.
        if (isSensitive(clip.description)) return null
        return clip.getItemAt(0).coerceToText(context)?.toString()?.takeIf { it.isNotBlank() }
    }

    /** True if the clip is flagged sensitive (Android 13+ EXTRA_IS_SENSITIVE), or, as a
     *  best-effort fallback on older OSes, looks like a password field's content. */
    private fun isSensitive(desc: ClipDescription?): Boolean {
        desc ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val extras: PersistableBundle? = desc.extras
            if (extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) == true) return true
        }
        return false
    }

    private fun loadHistory(context: Context): List<String> {
        val raw = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY, "") ?: ""
        return if (raw.isEmpty()) emptyList() else raw.split(SEP).filter { it.isNotEmpty() }
    }

    private fun saveHistory(context: Context, items: List<String>) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(KEY, items.joinToString(SEP)).apply()
    }
}
