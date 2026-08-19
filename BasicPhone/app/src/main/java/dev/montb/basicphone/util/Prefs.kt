package dev.montb.basicphone.util

import android.content.Context
import android.telephony.TelephonyManager

/**
 * App-managed voicemail number. This sidesteps the phone's wiped/broken voicemail
 * setting: we store our own number and dial it directly instead of trusting the
 * (possibly bad) system value.
 */
object Prefs {
    private const val FILE = "basicphone"
    private const val KEY_VOICEMAIL = "voicemail_number"
    private const val KEY_VOICEMAIL_PIN = "voicemail_pin"
    private const val KEY_SCREENING = "screening_mode"
    private const val KEY_SEARCH_ENGINE = "search_engine"

    /** How to handle incoming calls that FAIL STIR/SHAKEN attestation (see ScreeningService). */
    enum class ScreeningMode { OFF, SILENCE, REJECT }

    /** Which web search backs "look up this number online" (see NumberLookup). */
    enum class SearchEngine(val label: String) { GOOGLE("Google"), DUCKDUCKGO("DuckDuckGo") }

    /** Search engine for number look-ups; defaults to Google. */
    fun searchEngine(context: Context): SearchEngine =
        prefs(context).getString(KEY_SEARCH_ENGINE, null)
            ?.let { runCatching { SearchEngine.valueOf(it) }.getOrNull() }
            ?: SearchEngine.GOOGLE

    fun setSearchEngine(context: Context, engine: SearchEngine) =
        prefs(context).edit().putString(KEY_SEARCH_ENGINE, engine.name).apply()

    /** Spam-screening mode; defaults to OFF (opt-in, so calls are never blocked unexpectedly). */
    fun screeningMode(context: Context): ScreeningMode =
        prefs(context).getString(KEY_SCREENING, null)
            ?.let { runCatching { ScreeningMode.valueOf(it) }.getOrNull() }
            ?: ScreeningMode.OFF

    fun setScreeningMode(context: Context, mode: ScreeningMode) =
        prefs(context).edit().putString(KEY_SCREENING, mode.name).apply()

    // Per-SIM voicemail boxes (multi-SIM / eSIM-adapter): number + PIN keyed by subId.
    // Each carrier has its own voicemail box, so they can't share one number.
    private fun vmNumberKey(subId: Int) = "voicemail_number_$subId"
    private fun vmPinKey(subId: Int) = "voicemail_pin_$subId"

    /** This SIM's saved voicemail number. Falls back to the legacy global value (so an
     *  existing single-SIM setup keeps working until the user sets a per-SIM number). */
    fun savedVoicemailNumber(context: Context, subId: Int = -1): String? {
        if (subId >= 0) {
            prefs(context).getString(vmNumberKey(subId), null)?.takeIf { it.isNotBlank() }
                ?.let { return it }
        }
        return prefs(context).getString(KEY_VOICEMAIL, null)?.takeIf { it.isNotBlank() }
    }

    /** Optional PIN auto-sent (as DTMF) after the voicemail call connects. Per-SIM with
     *  a fallback to the legacy global PIN. Stored encrypted at rest (see [PinCrypto]). */
    fun voicemailPin(context: Context, subId: Int = -1): String? {
        if (subId >= 0) readPin(context, vmPinKey(subId))?.let { return it }
        return readPin(context, KEY_VOICEMAIL_PIN)
    }

    /** Read + decrypt a stored PIN. A pre-encryption plaintext value is returned as-is and
     *  transparently re-saved encrypted, so upgrading never loses the PIN. */
    private fun readPin(context: Context, key: String): String? {
        val stored = prefs(context).getString(key, null)?.takeIf { it.isNotBlank() } ?: return null
        PinCrypto.decrypt(stored)?.takeIf { it.isNotBlank() }?.let { return it }
        // Legacy plaintext: hand it back, and migrate it to encrypted-at-rest.
        prefs(context).edit().putString(key, PinCrypto.encrypt(stored)).apply()
        return stored
    }

    fun setVoicemailPin(context: Context, pin: String, subId: Int = -1) {
        val key = if (subId >= 0) vmPinKey(subId) else KEY_VOICEMAIL_PIN
        val trimmed = pin.trim()
        prefs(context).edit()
            .putString(key, if (trimmed.isEmpty()) null else PinCrypto.encrypt(trimmed))
            .apply()
    }

    /** The system's voicemail number, UNRELIABLE here (the user's was wiped). */
    fun systemVoicemailNumber(context: Context): String? = try {
        context.getSystemService(TelephonyManager::class.java)
            ?.voiceMailNumber
            ?.takeIf { it.isNotBlank() }
    } catch (e: SecurityException) {
        null
    }

    fun setVoicemailNumber(context: Context, number: String, subId: Int = -1) {
        val key = if (subId >= 0) vmNumberKey(subId) else KEY_VOICEMAIL
        prefs(context).edit().putString(key, number.trim()).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
