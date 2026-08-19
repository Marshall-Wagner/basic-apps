package dev.montb.basicphone.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.telecom.PhoneAccount
import android.telecom.TelecomManager

/**
 * Places a call, optionally on a specific SIM (subId). When a SIM is chosen we use
 * TelecomManager.placeCall with that SIM's PhoneAccountHandle; otherwise we fall back
 * to ACTION_CALL on the default SIM. Either way the system routes through the
 * telephony stack, so VoLTE is used automatically when enabled.
 */
fun placeCall(context: Context, number: String, subId: Int = -1) {
    val trimmed = number.trim()
    if (trimmed.isEmpty()) return
    // Build the tel: URI with Uri.fromParts. The 2nd arg is the OPAQUE scheme-specific
    // part, it is NOT parsed for a '#' fragment, so the whole "number,,pin#" (and a
    // leading '+' on international numbers) reaches telephony intact. (An earlier attempt
    // used Uri.parse("tel:"+Uri.encode(...)) to "preserve" the PIN's '#', but Uri.encode
    // turns '+' into %2B, which TelecomManager rejects, so normal calls showed the dial
    // screen but never connected. fromParts is correct for BOTH plain numbers and PINs.)
    val uri = Uri.fromParts("tel", trimmed, null)
    val telecom = context.getSystemService(TelecomManager::class.java)

    // Primary path: TelecomManager.placeCall. As the default dialer we handle this
    // ourselves with NO app-chooser. (ACTION_CALL, used only as a last resort below,
    // resolves to ANY tel-handling app, which is what was popping the "call with…"
    // chooser even though we're the default phone app.) A chosen SIM adds its handle;
    // without one, Telecom uses the default SIM.
    if (telecom != null) {
        try {
            val extras = Bundle()
            // Pick a SIM handle. If the caller chose one, use it; otherwise default to
            // the first active SIM. On a DUAL-SIM / eSIM-adapter phone, calling with NO
            // handle can leave Telecom unable to route, the call sits in DIALING forever
            // and never connects. Supplying a handle fixes that.
            val handle = if (subId >= 0) Sims.handleForSub(context, subId)
            else Sims.active(context).firstOrNull()?.let { Sims.handleForSub(context, it.subId) }
            handle?.let { extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, it) }
            telecom.placeCall(uri, extras)
            return
        } catch (e: SecurityException) {
            // CALL_PHONE not granted, fall through to the intent path.
        }
    }

    // Last resort only if Telecom failed (e.g. permission missing).
    try {
        context.startActivity(
            Intent(Intent.ACTION_CALL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (e: SecurityException) {
        context.startActivity(
            Intent(Intent.ACTION_DIAL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

/**
 * Calls voicemail reliably, in priority order:
 *   1. The number the user explicitly saved  (most reliable, fixes the wiped setting)
 *   2. The telephony "voicemail:" dial scheme on the chosen/own SIM (dials that SIM's
 *      mailbox without needing a number)
 * Does NOT fall back to the system voicemail number, because that's the broken value.
 * Returns false if neither path is available, so the caller can prompt for setup.
 */
fun callVoicemail(context: Context, subId: Int = -1): Boolean {
    val pin = Prefs.voicemailPin(context, subId)

    // 1. Explicitly saved number wins (this SIM's box, falling back to the legacy
    //    global one). If a PIN is saved, append it after pauses so the telephony stack
    //    auto-sends it as DTMF once connected. Each "," is a ~2s pause; we use THREE
    //    (~6s) to give the voicemail greeting time to finish before the PIN plays, too
    //    few pauses was the common "PIN didn't work" cause (tones sent before the prompt).
    //    Trailing "#" submits the PIN on most systems. If the user already put their own
    //    pauses in the saved number, we don't add ours.
    Prefs.savedVoicemailNumber(context, subId)?.let { saved ->
        val dial = when {
            pin == null -> saved
            saved.contains(',') -> "$saved$pin#"   // user controls their own pauses
            else -> "$saved,,,$pin#"
        }
        placeCall(context, dial, subId)
        return true
    }

    // 2. Voicemail dial scheme via Telecom (needs a SIM's PhoneAccountHandle).
    //    (PIN auto-send isn't supported on the voicemail: scheme; the in-call keypad
    //    is the fallback there.)
    val handle = if (subId >= 0) Sims.handleForSub(context, subId)
    else Sims.active(context).firstOrNull()?.let { Sims.handleForSub(context, it.subId) }
    if (handle != null) {
        try {
            val vmUri = Uri.fromParts(PhoneAccount.SCHEME_VOICEMAIL, "", null)
            val extras = Bundle().apply {
                putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle)
            }
            context.getSystemService(TelecomManager::class.java).placeCall(vmUri, extras)
            return true
        } catch (e: SecurityException) {
            // fall through
        } catch (e: IllegalArgumentException) {
            // some devices reject the voicemail scheme, fall through
        }
    }

    return false
}
