package dev.montb.basicsms.util

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.provider.Telephony

/** Helpers to become the default SMS app and to escape battery optimization. */
object DefaultAppHelper {

    /**
     * Are we the default SMS app?
     *
     * On Android 10+ the authoritative source is the SMS *role*. We deliberately do
     * NOT rely solely on [Telephony.Sms.getDefaultSmsPackage] (the legacy
     * `sms_default_application` secure setting): on some OEM ROMs (seen on the ROG 6
     * CN ROM) the role is held while the legacy value drifts to null, which made the
     * setup screen reappear / receiving silently break. We treat holding the role as
     * being the default.
     */
    fun isDefaultSmsApp(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val rm = context.getSystemService(RoleManager::class.java)
            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_SMS)) {
                return rm.isRoleHeld(RoleManager.ROLE_SMS)
            }
        }
        return context.packageName == Telephony.Sms.getDefaultSmsPackage(context)
    }

    /**
     * True when we hold the SMS role but the legacy `sms_default_application` value
     * disagrees (e.g. it's null). In that state the telephony stack on some ROMs may
     * not dispatch SMS_DELIVER, so we won't receive texts even though we look set up.
     * The app can't write the secure setting itself (no WRITE_SECURE_SETTINGS), so the
     * UI uses this to warn the user / offer re-running the default-app prompt.
     */
    fun hasDefaultSmsDrift(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val rm = context.getSystemService(RoleManager::class.java) ?: return false
        if (!rm.isRoleAvailable(RoleManager.ROLE_SMS) || !rm.isRoleHeld(RoleManager.ROLE_SMS)) return false
        return context.packageName != Telephony.Sms.getDefaultSmsPackage(context)
    }

    /** Intent that asks the user to make us the default SMS app. */
    fun requestDefaultSmsIntent(context: Context): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = context.getSystemService(RoleManager::class.java)
            roleManager.createRequestRoleIntent(RoleManager.ROLE_SMS)
        } else {
            @Suppress("DEPRECATION")
            Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT).apply {
                putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, context.packageName)
            }
        }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(PowerManager::class.java)
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /** Opens the system prompt to exempt us from Doze / battery optimization. */
    fun requestIgnoreBatteryOptimizationsIntent(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
}
