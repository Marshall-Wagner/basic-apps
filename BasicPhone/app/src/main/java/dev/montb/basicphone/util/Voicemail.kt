package dev.montb.basicphone.util

import android.content.Context
import android.telephony.TelephonyManager

/**
 * Recognizes voicemail numbers so the call log can show "Voicemail" instead of the
 * raw access number. Matches against the user's saved VM number AND each SIM's system
 * voicemail number, comparing on digits only (ignores +, spaces, formatting).
 */
object Voicemail {

    /** Set of known voicemail numbers (digits-only), cached per call-log render. */
    fun knownNumbers(context: Context): Set<String> {
        val out = HashSet<String>()
        Prefs.savedVoicemailNumber(context)?.let { out += digits(it) }
        // System voicemail number for each active SIM (may differ per carrier line).
        try {
            val tm = context.getSystemService(TelephonyManager::class.java)
            tm?.voiceMailNumber?.takeIf { it.isNotBlank() }?.let { out += digits(it) }
            Sims.active(context).forEach { sim ->
                runCatching {
                    tm?.createForSubscriptionId(sim.subId)?.voiceMailNumber
                        ?.takeIf { it.isNotBlank() }?.let { out += digits(it) }
                }
            }
        } catch (_: SecurityException) {
        }
        out.remove("")
        return out
    }

    fun isVoicemail(number: String, known: Set<String>): Boolean {
        val d = digits(number)
        return d.isNotEmpty() && d in known
    }

    private fun digits(s: String): String = s.filter { it.isDigit() }
}
