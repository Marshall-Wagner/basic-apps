package dev.montb.basicphone.util

import android.content.Context
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telephony.SubscriptionManager

/** One active SIM: its subscription id, physical slot, and carrier name. */
data class SimInfo(val subId: Int, val slotIndex: Int, val carrier: String)

/** SIM/carrier lookups via SubscriptionManager + TelecomManager. Needs READ_PHONE_STATE. */
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

    fun carrierForSub(context: Context, subId: Int): String? =
        active(context).firstOrNull { it.subId == subId }?.carrier

    /** A call-log PHONE_ACCOUNT_ID is the subId string for telephony accounts. */
    fun carrierForAccount(context: Context, accountId: String?): String? {
        val id = accountId?.toIntOrNull() ?: return null
        return carrierForSub(context, id)
    }

    /** PhoneAccountHandle for placing a call on a specific SIM (subId). */
    fun handleForSub(context: Context, subId: Int): PhoneAccountHandle? {
        return try {
            val tm = context.getSystemService(TelecomManager::class.java) ?: return null
            tm.callCapablePhoneAccounts.firstOrNull { it.id == subId.toString() }
        } catch (e: SecurityException) {
            null
        }
    }
}
