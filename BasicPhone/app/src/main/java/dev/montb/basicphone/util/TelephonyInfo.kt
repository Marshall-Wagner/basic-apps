package dev.montb.basicphone.util

import android.content.Context
import android.telephony.CarrierConfigManager

/**
 * Best-effort VoLTE indicator. NOTE: an app cannot turn VoLTE on/off, that's
 * controlled by the system + carrier provisioning. As the call placer we route
 * through Telecom, which uses VoLTE automatically when the system has it enabled.
 * This just reports what the carrier config advertises, for information only.
 */
object TelephonyInfo {

    /** null = unknown/not readable, true/false = carrier config VoLTE-available flag. */
    fun carrierVolteAvailable(context: Context): Boolean? {
        return try {
            val ccm = context.getSystemService(CarrierConfigManager::class.java) ?: return null
            val config = ccm.config ?: return null
            if (!config.containsKey(CarrierConfigManager.KEY_CARRIER_VOLTE_AVAILABLE_BOOL)) return null
            config.getBoolean(CarrierConfigManager.KEY_CARRIER_VOLTE_AVAILABLE_BOOL)
        } catch (e: SecurityException) {
            null
        } catch (e: Exception) {
            null
        }
    }
}
