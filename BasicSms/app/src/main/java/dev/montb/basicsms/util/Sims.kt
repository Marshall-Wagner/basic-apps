package dev.montb.basicsms.util

import android.content.Context
import android.telephony.SubscriptionManager

/** One active SIM: its subscription id, physical slot, and carrier name. */
data class SimInfo(val subId: Int, val slotIndex: Int, val carrier: String)

/** Reads the active SIMs via SubscriptionManager. Needs READ_PHONE_STATE. */
object Sims {

    fun active(context: Context): List<SimInfo> {
        val sm = context.getSystemService(SubscriptionManager::class.java) ?: return emptyList()
        return try {
            sm.activeSubscriptionInfoList?.map { info ->
                SimInfo(
                    subId = info.subscriptionId,
                    slotIndex = info.simSlotIndex,
                    carrier = info.carrierName?.toString()?.takeIf { it.isNotBlank() }
                        ?: "SIM ${info.simSlotIndex + 1}"
                )
            } ?: emptyList()
        } catch (e: SecurityException) {
            emptyList()
        }
    }

    fun carrierFor(context: Context, subId: Int): String? =
        active(context).firstOrNull { it.subId == subId }?.carrier
}
