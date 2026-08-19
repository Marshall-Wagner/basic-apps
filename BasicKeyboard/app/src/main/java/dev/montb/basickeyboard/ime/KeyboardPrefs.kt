package dev.montb.basickeyboard.ime

import android.content.Context

/**
 * User preferences for the keyboard. Currently just the row height, chosen from a few
 * discrete sizes (the user tunes this by feel). Stored in private prefs; read by the
 * KeyboardView when it lays out, and written by the setup screen's size control.
 */
object KeyboardPrefs {

    private const val FILE = "keyboard_prefs"
    private const val KEY_ROW_HEIGHT = "row_height_dp"
    private const val KEY_NARROW_MODIFIERS = "narrow_modifiers"
    private const val KEY_SQUARE_KEYS = "square_keys"
    private const val KEY_COMPACT_GRID = "compact_grid"
    private const val KEY_VIBRATION = "vibration_enabled"
    private const val KEY_PASSWORD_MANAGER = "password_manager"

    /** The discrete height options, in dp. 62 is the long-settled default; 72 was once
     *  tried and felt too tall, so the top option is a gentler 74 and we center on 62. */
    val HEIGHT_OPTIONS = listOf(
        56 to "Compact",
        62 to "Default",
        68 to "Tall",
        74 to "X-Tall"
    )

    const val DEFAULT_ROW_HEIGHT = 62

    fun rowHeightDp(context: Context): Int =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getInt(KEY_ROW_HEIGHT, DEFAULT_ROW_HEIGHT)

    fun setRowHeightDp(context: Context, dp: Int) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putInt(KEY_ROW_HEIGHT, dp).apply()
    }

    /** When true, the wide modifier keys (Shift/Backspace/123/=\</Enter) are the same
     *  width as letters (weight 1x) instead of 1.5x, easier on letter muscle memory. */
    fun narrowModifiers(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getBoolean(KEY_NARROW_MODIFIERS, false)

    fun setNarrowModifiers(context: Context, narrow: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_NARROW_MODIFIERS, narrow).apply()
    }

    /** When true, draw HTC-style keys: a small corner radius (squarer, less "bubbly")
     *  and a slightly wider gap so each key is a touch smaller. Default keeps the
     *  rounded, larger keys. */
    fun squareKeys(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getBoolean(KEY_SQUARE_KEYS, false)

    fun setSquareKeys(context: Context, square: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_SQUARE_KEYS, square).apply()
    }

    /** When true, lay the keyboard out like Simple Keyboard (F-Droid): a hairline gap
     *  instead of the roomy default, and every row sharing one column width so shorter
     *  rows (e.g. asdf…) are centered under the top row instead of being stretched wide. */
    fun compactGrid(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getBoolean(KEY_COMPACT_GRID, false)

    fun setCompactGrid(context: Context, compact: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_COMPACT_GRID, compact).apply()
    }

    /** Haptic feedback (vibration) on each keypress. On by default. */
    fun vibrationEnabled(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getBoolean(KEY_VIBRATION, true)

    fun setVibrationEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_VIBRATION, enabled).apply()
    }

    /** Preferred password-manager package for the shortcut key; null = auto (first installed). */
    fun passwordManager(context: Context): String? =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(KEY_PASSWORD_MANAGER, null)?.takeIf { it.isNotBlank() }

    fun setPasswordManager(context: Context, pkg: String?) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putString(KEY_PASSWORD_MANAGER, pkg).apply()
    }
}
