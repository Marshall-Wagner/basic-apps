package dev.montb.basicphone.incall

import android.os.Build
import android.telecom.Call
import android.telecom.CallScreeningService
import android.telecom.Connection
import android.util.Log
import dev.montb.basicphone.util.Prefs

/**
 * Screens incoming calls against their STIR/SHAKEN attestation, the same ✓/⚠ signal the
 * in-call screen shows. A **FAILED** result means the carrier cryptographically determined the
 * caller ID is spoofed; that's a strong spam signal legitimate calls essentially never trip
 * (they come through as PASSED or NOT_VERIFIED). Depending on the user's setting we silence the
 * ring or reject such calls; everything else is allowed through untouched.
 *
 * This is invoked because BasicPhone is the **default dialer** (no extra role needed). It runs
 * entirely on-device, no number lists, no network, so it has no dependency on any server
 * (unlike the abandoned Carrion app the user was relying on).
 */
class ScreeningService : CallScreeningService() {

    override fun onScreenCall(callDetails: Call.Details) {
        val response = CallResponse.Builder()

        // Only ever touch INCOMING calls; allow outgoing (and anything pre-Q we can't classify).
        val incoming = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            callDetails.callDirection == Call.Details.DIRECTION_INCOMING
        val mode = Prefs.screeningMode(this)
        val failed = attestationFailed(callDetails)
        var action = "allow"

        if (incoming && mode != Prefs.ScreeningMode.OFF && failed) {
            when (mode) {
                Prefs.ScreeningMode.SILENCE -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) response.setSilenceCall(true)
                    action = "silence"
                }
                Prefs.ScreeningMode.REJECT -> {
                    response.setDisallowCall(true)
                    response.setRejectCall(true)
                    response.setSkipNotification(true) // don't buzz about a blocked spam call…
                    response.setSkipCallLog(false)     // …but keep a log entry so it's visible
                    action = "reject"
                }
                Prefs.ScreeningMode.OFF -> Unit
            }
        }

        // Diagnostic: confirms Telecom actually binds our screener on this ROM, and shows the
        // STIR/SHAKEN status real calls carry. (-1 = pre-R / unknown.)
        val status = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            callDetails.callerNumberVerificationStatus else -1
        Log.i("BasicPhoneScreen", "onScreenCall incoming=$incoming verify=$status mode=$mode -> $action")

        // respondToCall MUST be called for every screened call or the call would hang.
        respondToCall(callDetails, response.build())
    }

    /** True only when the carrier reports STIR/SHAKEN verification FAILED (spoofed caller ID). */
    private fun attestationFailed(details: Call.Details): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            details.callerNumberVerificationStatus == Connection.VERIFICATION_STATUS_FAILED
}
