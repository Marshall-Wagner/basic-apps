package dev.montb.basickeyboard.ime

import android.content.Context
import android.content.Intent

/**
 * The password-manager shortcut opens the first of these that's installed, so it isn't locked
 * to one vendor. Every package here is also declared in the manifest's <queries> block,
 * without that, Android 11+ package visibility hides them and getLaunchIntentForPackage()
 * returns null even when the app is installed.
 */
object PasswordManagers {
    val PACKAGES = listOf(
        "proton.android.pass",                        // Proton Pass
        "com.x8bit.bitwarden",                        // Bitwarden
        "com.kunzisoft.keepass.free",                 // KeePassDX
        "com.kunzisoft.keepass.libre",                // KeePassDX (F-Droid)
        "keepass2android.keepass2android",            // Keepass2Android
        "keepass2android.keepass2android_nonet",      // Keepass2Android Offline
        "io.enpass.app",                              // Enpass
        "com.onepassword.android",                    // 1Password
        "com.lastpass.lpandroid",                     // LastPass
        "com.dashlane",                               // Dashlane
        "com.callpod.android_apps.keeper",            // Keeper
        "com.nordpass.android.app.password.manager"   // NordPass
    )

    /** An installed, launchable password manager. */
    data class Installed(val pkg: String, val label: String)

    /** Supported managers that are actually installed (visible), with their display names. */
    fun installed(context: Context): List<Installed> {
        val pm = context.packageManager
        return PACKAGES.mapNotNull { pkg ->
            runCatching {
                Installed(pkg, pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString())
            }.getOrNull()
        }
    }

    /** Launch intent for the user's chosen manager (if set + installed), otherwise the first
     *  installed in priority order, otherwise null. */
    fun launchIntent(context: Context): Intent? {
        val pm = context.packageManager
        KeyboardPrefs.passwordManager(context)?.let { pref ->
            pm.getLaunchIntentForPackage(pref)?.let { return it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        }
        for (pkg in PACKAGES) {
            pm.getLaunchIntentForPackage(pkg)?.let { return it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        }
        return null
    }
}
