package dev.montb.basicphone.util

import android.content.Context
import android.telephony.TelephonyManager

/**
 * Best-effort voicemail-number guessing. This is INHERENTLY unreliable:
 *  - The system value (TelephonyManager.getVoiceMailNumber) is often wiped/blank on
 *    this phone, the whole reason BasicPhone stores its own number.
 *  - Carrier NAME lies: MVNOs report their host network (Mint shows as "T-Mobile"),
 *    so a name→number table can guess the wrong full access number.
 *
 * So we offer TWO methods and let the user toggle between them in setup:
 *  - SHORTCUT ("*86"): carrier-routed to YOUR box, works across MVNOs (incl. Mint on
 *    T-Mobile). The safe default.
 *  - FULL_NUMBER: a known full access number per carrier. More "direct" but breaks for
 *    MVNOs / outdated numbers. Use only if the shortcut fails for you.
 *
 * Whatever we produce is a SUGGESTION pre-filled into the editable setup field, never
 * auto-dialed without the user confirming.
 */
object VoicemailNumbers {

    enum class Method { SHORTCUT, FULL_NUMBER }

    /** The universal-ish voicemail shortcut. *86 reaches your own box on AT&T, T-Mobile,
     *  Verizon and their MVNOs (Mint rides T-Mobile, so *86 still hits the Mint box). */
    private const val SHORTCUT = "*86"

    /** Known full voicemail access numbers by host network. These are the RISKY option
     *  (may not work for MVNOs like Mint, may go stale). Matched loosely by name. */
    private val FULL_NUMBERS = listOf(
        "at&t" to "1-908-450-0148",
        "t-mobile" to "805-637-7243",
        "verizon" to "*86",        // Verizon's own is effectively the shortcut
        "mint" to "*86"            // MVNO, shortcut is the only reliable route
    )

    /**
     * A suggested voicemail number for a SIM. Priority:
     *  1. The system per-SIM value if present (correct when set, never a wrong guess).
     *  2. The chosen [method]: SHORTCUT (*86) or a name-matched FULL_NUMBER.
     * Returns null only if nothing can be guessed.
     */
    fun suggest(context: Context, subId: Int, carrierName: String?, method: Method): String? {
        systemValue(context, subId)?.let { return it }
        return when (method) {
            Method.SHORTCUT -> SHORTCUT
            Method.FULL_NUMBER -> {
                val name = carrierName?.lowercase().orEmpty()
                FULL_NUMBERS.firstOrNull { name.contains(it.first) }?.second ?: SHORTCUT
            }
        }
    }

    /** Per-SIM system voicemail number, if the OS has one (often blank on this phone). */
    private fun systemValue(context: Context, subId: Int): String? = try {
        val tm = context.getSystemService(TelephonyManager::class.java) ?: return null
        val perSim = if (subId >= 0) tm.createForSubscriptionId(subId) else tm
        perSim.voiceMailNumber?.takeIf { it.isNotBlank() }
    } catch (e: SecurityException) {
        null
    }
}
