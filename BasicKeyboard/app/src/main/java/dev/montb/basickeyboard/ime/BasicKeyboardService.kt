package dev.montb.basickeyboard.ime

import android.content.ClipboardManager
import android.inputmethodservice.InputMethodService
import android.inputmethodservice.InputMethodService.Insets
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo

/**
 * The keyboard. Switches between language layouts (English/Russian), a symbols layer,
 * and an emoji panel, sending characters to the focused field via the input connection.
 */
class BasicKeyboardService : InputMethodService(), KeyboardView.Listener {

    private enum class Lang { ENGLISH, RUSSIAN }
    private enum class Mode { LETTERS, SYMBOLS, SYMBOLS2 }

    private var lang = Lang.ENGLISH
    private var mode = Mode.LETTERS
    private var shifted = false
    private var capsLock = false
    private var passwordField = false   // current field is a password/secure input

    private lateinit var keyboardView: KeyboardView
    private lateinit var topStrip: TopStripView
    private lateinit var root: android.widget.LinearLayout  // [topStrip] + swappable body
    private var dark = true

    private val vibrator: Vibrator? by lazy {
        @Suppress("DEPRECATION")
        getSystemService(VIBRATOR_SERVICE) as? Vibrator
    }

    private val clipboardManager: ClipboardManager? by lazy {
        getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager
    }
    // Live clipboard updates: when text is copied, even while the keyboard is already up on the
    // same field (where onStartInput won't fire again), fold it into history and refresh the top
    // strip, so the newest copy appears immediately instead of stale older entries.
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        ClipboardStore.capture(this)
        if (::topStrip.isInitialized) topStrip.refresh(hideChips = passwordField)
    }

    // The row-height the current input view was built with, so we can rebuild when the
    // user changes the size in the setup screen.
    private var builtRowHeight = -1
    // Same idea for the square-keys (HTC-style) toggle: radius/gap are fixed when the view
    // is built, so we rebuild if the setting changed.
    private var builtSquareKeys = false
    // And for the Simple-Keyboard grid layout (gap/centering fixed at build time).
    private var builtCompactGrid = false

    override fun onCreate() {
        super.onCreate()
        clipboardManager?.addPrimaryClipChangedListener(clipListener)
    }

    override fun onDestroy() {
        clipboardManager?.removePrimaryClipChangedListener(clipListener)
        super.onDestroy()
    }

    override fun onCreateInputView(): View {
        dark = isNightMode()
        builtRowHeight = KeyboardPrefs.rowHeightDp(this)
        builtSquareKeys = KeyboardPrefs.squareKeys(this)
        builtCompactGrid = KeyboardPrefs.compactGrid(this)
        keyboardView = KeyboardView(this, this).apply {
            dark = this@BasicKeyboardService.dark
        }
        topStrip = TopStripView(
            this, dark,
            onPaste = { commit(it) },
            onClearAll = { ClipboardStore.clear(this); topStrip.refresh(hideChips = passwordField) },
            onOpenClipboard = { showClipboard() },
            onOpenPassword = { openPasswordManager() },
            onSettings = { openSettings() }
        )
        root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(topStrip)
            addView(keyboardView)
        }
        applyLayout()
        return root
    }

    /** Swap the body (keyboard / emoji / clipboard) below the persistent top strip. */
    private fun setBody(view: View) {
        if (!::root.isInitialized) return
        // child 0 = topStrip, child 1 = body
        if (root.childCount > 1) root.removeViewAt(1)
        root.addView(view)
    }

    private fun openSettings() {
        startActivity(
            android.content.Intent(this, dev.montb.basickeyboard.ui.MainActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** Open the user's password manager (whichever supported one is installed) so they can
     *  copy a password, then paste it back via the clipboard strip. A keyboard can't read the
     *  vault itself (by design), so this is just a convenience shortcut. */
    private fun openPasswordManager() {
        val intent = PasswordManagers.launchIntent(this)
        if (intent != null) {
            startActivity(intent)
        } else {
            android.widget.Toast.makeText(
                this, "No supported password manager installed", android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    /** Rebuild the input view when day/night (or other config) changes, so the
     *  keyboard re-themes correctly, the ROG 6 supports scheduled dark mode. */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        val nowDark = isNightMode()
        if (nowDark != dark) {
            dark = nowDark
            if (::keyboardView.isInitialized) {
                keyboardView.dark = dark
                setInputView(onCreateInputView())
            }
        }
    }

    private fun isNightMode(): Boolean =
        (resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES

    /**
     * Force the entire keyboard window to be touchable. The default region is derived from
     * the input view's measured position, which some host apps (notably QQ, with its unusual
     * window layout / floating panels) can leave collapsed, the keys then render but receive
     * no touches at all (no haptic, nothing typed), recoverable only by rebuilding the IME.
     * Our window is just the top strip + key grid with no transparent gaps, so making the
     * whole frame touchable is safe and can't swallow touches meant for the app behind it.
     */
    override fun onComputeInsets(outInsets: Insets) {
        super.onComputeInsets(outInsets)
        outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_FRAME
        outInsets.contentTopInsets = outInsets.visibleTopInsets
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        // If the user changed the keyboard height in the setup screen, rebuild the view
        // so the new size takes effect the next time the keyboard opens.
        if (builtRowHeight != KeyboardPrefs.rowHeightDp(this) ||
            builtSquareKeys != KeyboardPrefs.squareKeys(this) ||
            builtCompactGrid != KeyboardPrefs.compactGrid(this)) {
            setInputView(onCreateInputView())
        } else if (::keyboardView.isInitialized) {
            // Re-assert the keyboard as the visible body every time it's shown. Some apps
            // (notably QQ) hide the system keyboard behind their own emoji / voice / "+"
            // panels and then re-show it for the SAME field, firing onStartInputView WITHOUT
            // a fresh onStartInput. Without this, the body could be left on a detached
            // emoji/clipboard panel from earlier, which looks like the keyboard "stopped
            // working" (visible, but taps go nowhere). applyLayout() restores the key grid.
            applyLayout()
        }
        // Fold the current clipboard into history AND reflect it, every time the keyboard
        // shows. This is what actually accumulates history: the OS primary-clip listener does
        // not fire for copies made in other apps (e.g. QQ) while we were hidden, so without
        // capturing on show the strip would only ever echo the single live clip.
        ClipboardStore.capture(this)
        if (::topStrip.isInitialized) topStrip.refresh(hideChips = passwordField)
    }

    /** The keyboard is being hidden. Cancel any in-flight key repeat / long-press so a held
     *  backspace whose UP the host app swallowed (QQ hides the keyboard mid-press) can't keep
     *  deleting whole words once we're no longer on screen. */
    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        if (::keyboardView.isInitialized) keyboardView.cancelPending()
    }

    override fun onStartInput(info: EditorInfo?, restarting: Boolean) {
        super.onStartInput(info, restarting)
        // A new field: drop any timer left over from the previous one before we start.
        if (::keyboardView.isInitialized) keyboardView.cancelPending()
        // Reset to letters each time a new field is focused.
        mode = Mode.LETTERS
        shifted = false
        capsLock = false
        // Don't surface clipboard chips while a password field is focused, avoids
        // showing/leaking copied secrets in a login context.
        passwordField = info?.let { isPasswordField(it.inputType) } ?: false
        if (::keyboardView.isInitialized) {
            applyLayout()
            // A new field usually follows a fresh copy elsewhere (the copy -> focus field ->
            // paste flow). Capture it now so history accumulates without relying on the OS
            // clip listener, then refresh the strip's chips.
            ClipboardStore.capture(this)
            if (::topStrip.isInitialized) topStrip.refresh(hideChips = passwordField)
        }
    }

    /** True for password / hidden input types (text, web, number/PIN passwords). */
    private fun isPasswordField(inputType: Int): Boolean {
        val cls = inputType and android.text.InputType.TYPE_MASK_CLASS
        val variation = inputType and android.text.InputType.TYPE_MASK_VARIATION
        return when {
            cls == android.text.InputType.TYPE_CLASS_TEXT && (
                variation == android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == android.text.InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
            ) -> true
            cls == android.text.InputType.TYPE_CLASS_NUMBER &&
                variation == android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD -> true
            else -> false
        }
    }

    // --- KeyboardView.Listener ---

    override fun onKey(action: KeyAction) {
        haptic()
        when (action) {
            is KeyAction.Char -> {
                commit(action.text)
                if (shifted && !capsLock) { shifted = false; applyShift() }
            }
            KeyAction.Backspace -> backspace()
            KeyAction.Space -> commit(" ")
            KeyAction.Enter -> onEnter()
            KeyAction.Shift -> toggleShift()
            KeyAction.SymbolsLayer -> nextSymbolPage()
            KeyAction.NumbersToggle -> toggleNumbers()
            KeyAction.LetterLayer -> { mode = Mode.LETTERS; applyLayout() }
            KeyAction.Language -> cycleLanguage()
            KeyAction.Emoji -> showEmoji()
            KeyAction.Clipboard -> showClipboard()
        }
    }

    override fun onKeyText(text: String) {
        haptic()
        commit(text)
        if (shifted && !capsLock) { shifted = false; applyShift() }
    }

    /** Held-backspace bulk delete: remove the previous whole word (plus trailing
     *  whitespace), so a sustained hold clears text much faster than char-by-char. */
    override fun onBackspaceWord() {
        haptic()
        val ic = currentInputConnection ?: return
        val selected = ic.getSelectedText(0)
        if (!selected.isNullOrEmpty()) { ic.commitText("", 1); return }
        // Look back at up to 64 chars and find the start of the current word.
        val before = ic.getTextBeforeCursor(64, 0) ?: ""
        if (before.isEmpty()) return
        var i = before.length
        // Eat trailing whitespace, then the run of non-whitespace word chars.
        while (i > 0 && before[i - 1].isWhitespace()) i--
        while (i > 0 && !before[i - 1].isWhitespace()) i--
        val deleteCount = before.length - i
        ic.deleteSurroundingText(if (deleteCount > 0) deleteCount else 1, 0)
    }

    // --- actions ---

    private fun commit(text: String) {
        currentInputConnection?.commitText(text, 1)
    }

    private fun backspace() {
        val ic = currentInputConnection ?: return
        val selected = ic.getSelectedText(0)
        if (selected.isNullOrEmpty()) {
            ic.deleteSurroundingText(1, 0)
        } else {
            ic.commitText("", 1)
        }
    }

    private fun onEnter() {
        val action = currentInputEditorInfo?.imeOptions ?: 0
        val actionId = action and EditorInfo.IME_MASK_ACTION
        if (actionId != EditorInfo.IME_ACTION_NONE &&
            (action and EditorInfo.IME_FLAG_NO_ENTER_ACTION) == 0
        ) {
            currentInputConnection?.performEditorAction(actionId)
        } else {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
        }
    }

    private fun toggleShift() {
        // tap = shift once; quick double handled simply: if already shifted, caps-lock.
        if (shifted && !capsLock) capsLock = true
        else if (capsLock) { capsLock = false; shifted = false }
        else shifted = true
        applyShift()
    }

    private fun applyShift() {
        keyboardView.shifted = shifted || capsLock
    }

    /** The bottom-left 123/ABC key: a plain toggle between letters and the numbers/
     *  symbols page 1. Never advances to the extra symbol pages. */
    private fun toggleNumbers() {
        mode = if (mode == Mode.LETTERS) Mode.SYMBOLS else Mode.LETTERS
        applyLayout()
    }

    /** The row-3 "=\<" / "?123" key: flips between the two extra symbol pages. It only
     *  appears on symbol pages, so it just swaps page 1 <-> page 2 (back to letters is
     *  the 123/ABC key's job). */
    private fun nextSymbolPage() {
        mode = if (mode == Mode.SYMBOLS) Mode.SYMBOLS2 else Mode.SYMBOLS
        applyLayout()
    }

    private fun cycleLanguage() {
        lang = if (lang == Lang.ENGLISH) Lang.RUSSIAN else Lang.ENGLISH
        mode = Mode.LETTERS
        applyLayout()
    }

    private fun showEmoji() {
        val emojiView = EmojiView(
            this, dark,
            onEmoji = { commit(it) },
            onBack = { setBody(keyboardView) }
        )
        setBody(emojiView)
    }

    private fun showClipboard() {
        // Fold the live system clipboard into our saved history, then show the panel.
        ClipboardStore.capture(this)
        topStrip.refresh(hideChips = passwordField)
        val view = ClipboardView(
            this, dark,
            onPaste = { commit(it); setBody(keyboardView) },
            onClearAll = { ClipboardStore.clear(this); topStrip.refresh(hideChips = passwordField); setBody(keyboardView) },
            onBack = { setBody(keyboardView) }
        )
        setBody(view)
    }

    private fun applyLayout() {
        // Narrow (1x) vs. wide (1.5x) modifier keys, per the user's setup-screen toggle.
        val mw = if (KeyboardPrefs.narrowModifiers(this)) Layouts.NARROW_MOD else Layouts.WIDE_MOD
        keyboardView.layout = when (mode) {
            Mode.SYMBOLS -> Layouts.symbols(mw)
            Mode.SYMBOLS2 -> Layouts.symbols2(mw)
            Mode.LETTERS -> when (lang) {
                Lang.ENGLISH -> Layouts.english(mw)
                Lang.RUSSIAN -> Layouts.russian(mw)
            }
        }
        applyShift()
        // Make sure the keyboard (not an emoji/clipboard panel) is the visible body.
        if (::root.isInitialized) setBody(keyboardView)
    }

    private fun haptic() {
        if (!KeyboardPrefs.vibrationEnabled(this)) return
        if (::keyboardView.isInitialized &&
            keyboardView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        ) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createOneShot(12, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION") vibrator?.vibrate(12)
        }
    }
}
