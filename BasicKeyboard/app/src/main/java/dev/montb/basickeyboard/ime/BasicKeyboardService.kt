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
 * An input mode offered by the globe key. [label] goes on the space bar (kept short so it fits)
 * and doubles as a script sample; [description] names it in the language picker.
 */
internal enum class Lang(val label: String, val description: String) {
    ENGLISH("English", "English"),
    RUSSIAN("Русский", "Russian"),
    PINYIN("中文", "Chinese (pinyin)"),
    HIRAGANA("あ", "Japanese (hiragana)"),
    KATAKANA("ア", "Japanese (katakana)")
}

/**
 * The keyboard. Switches between input modes (English, Russian, Chinese pinyin, Japanese kana),
 * a symbols layer, and an emoji panel, sending characters to the focused field via the input
 * connection.
 */
class BasicKeyboardService : InputMethodService(), KeyboardView.Listener {

    private enum class Mode { LETTERS, SYMBOLS, SYMBOLS2 }
    private enum class NumPad { NONE, NUMERIC, PHONE }

    private var lang = Lang.ENGLISH
    private var mode = Mode.LETTERS
    private var shifted = false
    private var capsLock = false
    private var passwordField = false   // current field is a password/secure input
    private var numPad = NumPad.NONE    // current field wants a number pad (numeric / phone)

    // Chinese mode: the raw latin letters typed so far, shown underlined in the field via
    // setComposingText until a candidate (or the pinyin itself) is committed. Empty = not composing.
    private val composing = StringBuilder()

    private lateinit var keyboardView: KeyboardView
    private lateinit var topStrip: TopStripView
    private lateinit var candidatesView: CandidatesView
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
        candidatesView = CandidatesView(this, dark, onPick = { pickCandidate(it) })
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
        // child 0 = strip, child 1 = body
        if (root.childCount > 1) root.removeViewAt(1)
        root.addView(view)
    }

    /** Swap the top strip between the clipboard bar and the pinyin candidates bar. No-op when
     *  the requested view is already showing, so it can be called freely. */
    private fun setStrip(view: View) {
        if (!::root.isInitialized) return
        if (root.childCount > 0 && root.getChildAt(0) === view) return
        if (root.childCount > 0) root.removeViewAt(0)
        root.addView(view, 0)
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
        // Don't leave a half-typed pinyin composing region behind in the field.
        clearComposing()
    }

    override fun onStartInput(info: EditorInfo?, restarting: Boolean) {
        super.onStartInput(info, restarting)
        // A new field: drop any timer left over from the previous one before we start.
        if (::keyboardView.isInitialized) keyboardView.cancelPending()
        // Drop any half-typed pinyin from the previous field; that input connection is gone.
        composing.setLength(0)
        // Reset to letters each time a new field is focused.
        mode = Mode.LETTERS
        shifted = false
        capsLock = false
        // Don't surface clipboard chips while a password field is focused, avoids
        // showing/leaking copied secrets in a login context.
        passwordField = info?.let { isPasswordField(it.inputType) } ?: false
        // Fields that only take numbers (number / date-time / phone) get a number pad
        // instead of the letter layout, so there's no character input to wade through.
        numPad = info?.let { numPadFor(it.inputType) } ?: NumPad.NONE
        if (::keyboardView.isInitialized) {
            applyLayout()
            // A new field usually follows a fresh copy elsewhere (the copy -> focus field ->
            // paste flow). Capture it now so history accumulates without relying on the OS
            // clip listener, then refresh the strip's chips.
            ClipboardStore.capture(this)
            showClipboardStrip()
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

    /** Which number pad (if any) a field wants, from its input class: a dial pad for phone
     *  fields, a numeric pad for number and date/time fields (a numeric PIN included, which
     *  gives a secure keypad), and none for ordinary text. */
    private fun numPadFor(inputType: Int): NumPad =
        when (inputType and android.text.InputType.TYPE_MASK_CLASS) {
            android.text.InputType.TYPE_CLASS_PHONE -> NumPad.PHONE
            android.text.InputType.TYPE_CLASS_NUMBER,
            android.text.InputType.TYPE_CLASS_DATETIME -> NumPad.NUMERIC
            else -> NumPad.NONE
        }

    // --- KeyboardView.Listener ---

    override fun onKey(action: KeyAction) {
        haptic()
        when (action) {
            is KeyAction.Char -> {
                val ch = action.text
                // Shift/caps always types the latin letter, so names and URLs still work without
                // leaving Chinese or Japanese mode.
                val plainLetter = ch.length == 1 && ch[0] in 'a'..'z' && !shifted && !capsLock
                when {
                    // Chinese: letters build the pinyin buffer, converted by candidate choice.
                    pinyinActive() && plainLetter -> {
                        composing.append(ch)
                        updateComposing()
                    }
                    // Japanese: letters build a romaji buffer that turns into kana as soon as a
                    // syllable completes; only an incomplete tail stays pending.
                    japaneseActive() && plainLetter -> {
                        composing.append(ch)
                        convertKana()
                    }
                    // The , and . keys produce Japanese punctuation in kana mode.
                    japaneseActive() && (ch == "," || ch == ".") -> {
                        commitComposingRaw()
                        commit(if (ch == ",") "、" else "。")
                    }
                    else -> {
                        commitComposingRaw()
                        commit(ch)
                        if (shifted && !capsLock) { shifted = false; applyShift() }
                    }
                }
            }
            KeyAction.Backspace -> {
                // While composing, backspace edits the pinyin rather than the field's text.
                if (composing.isNotEmpty()) {
                    composing.deleteCharAt(composing.length - 1)
                    updateComposing()
                } else backspace()
            }
            KeyAction.Space -> when {
                composing.isEmpty() -> commit(" ")
                // Chinese: space accepts the best candidate, the standard pinyin-IME gesture.
                pinyinActive() -> {
                    val best = PinyinEngine.candidates(composing.toString(), 1).firstOrNull()
                    if (best != null) pickCandidate(best) else commitComposingRaw()
                }
                // Japanese: kana conversion is unambiguous, so there is nothing to choose;
                // space just settles the pending romaji (never consults the pinyin dictionary).
                else -> commitComposingRaw()
            }
            // Enter while composing types the pinyin as-is: the escape hatch when no candidate fits.
            KeyAction.Enter -> if (composing.isNotEmpty()) commitComposingRaw() else onEnter()
            KeyAction.Shift -> toggleShift()
            KeyAction.SymbolsLayer -> { commitComposingRaw(); nextSymbolPage() }
            KeyAction.NumbersToggle -> { commitComposingRaw(); toggleNumbers() }
            KeyAction.LetterLayer -> {
                commitComposingRaw(); numPad = NumPad.NONE; mode = Mode.LETTERS; applyLayout()
            }
            KeyAction.Language -> { commitComposingRaw(); showLanguagePicker() }
            KeyAction.Emoji -> { commitComposingRaw(); showEmoji() }
            KeyAction.Clipboard -> { commitComposingRaw(); showClipboard() }
        }
    }

    override fun onKeyText(text: String) {
        haptic()
        // A long-press popup (number / accent) is literal text, so finish any pinyin first.
        commitComposingRaw()
        commit(text)
        if (shifted && !capsLock) { shifted = false; applyShift() }
    }

    /** Held-backspace bulk delete: remove the previous whole word (plus trailing
     *  whitespace), so a sustained hold clears text much faster than char-by-char. */
    override fun onBackspaceWord() {
        haptic()
        // Held backspace while composing clears the whole pinyin buffer, never the field's text.
        if (composing.isNotEmpty()) {
            composing.setLength(0)
            updateComposing()
            return
        }
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

    // --- pinyin composing ---

    /** Chinese mode, on the letter layout (not a symbols page or a number-pad field). */
    private fun pinyinActive(): Boolean =
        lang == Lang.PINYIN && mode == Mode.LETTERS && numPad == NumPad.NONE

    /** Japanese mode (either kana script), on the letter layout. */
    private fun japaneseActive(): Boolean =
        (lang == Lang.HIRAGANA || lang == Lang.KATAKANA) &&
            mode == Mode.LETTERS && numPad == NumPad.NONE

    /**
     * Japanese: take any completed syllables out of the romaji buffer and type them as kana,
     * leaving only an incomplete tail pending. commitText replaces the pending composing region,
     * so "kka" lands as っか with "k" briefly showing underlined in between.
     */
    private fun convertKana() {
        val (kana, rest) = KanaEngine.convert(composing.toString())
        if (kana.isNotEmpty()) {
            commit(if (lang == Lang.KATAKANA) KanaEngine.toKatakana(kana) else kana)
        }
        composing.setLength(0)
        composing.append(rest)
        updateComposing()
    }

    /** Show the in-progress pinyin underlined in the field and refresh the candidates bar. */
    private fun updateComposing() {
        val ic = currentInputConnection
        if (composing.isEmpty()) {
            ic?.finishComposingText()
            showClipboardStrip()
            return
        }
        ic?.setComposingText(composing, 1)
        // Only Chinese needs a candidates bar. Kana conversion is unambiguous, so the pending
        // romaji showing underlined in the field is feedback enough.
        if (pinyinActive() && ::candidatesView.isInitialized) {
            val typed = composing.toString()
            candidatesView.show(typed, PinyinEngine.candidates(typed))
            setStrip(candidatesView)
        }
    }

    /**
     * Commit a chosen Hanzi. It consumed [PinyinEngine.Candidate.keyLen] letters, so any leftover
     * pinyin keeps composing, e.g. typing "nihaoma" and picking 你好 leaves "ma" still pending.
     */
    private fun pickCandidate(c: PinyinEngine.Candidate) {
        haptic()
        currentInputConnection?.commitText(c.word, 1)
        val leftover = if (c.keyLen in 1 until composing.length) composing.substring(c.keyLen) else ""
        composing.setLength(0)
        composing.append(leftover)
        updateComposing()
    }

    /** Settle whatever is pending: pinyin types its letters as-is, Japanese resolves a trailing
     *  lone "n" to ん (anything else stays literal). */
    private fun commitComposingRaw() {
        if (composing.isEmpty()) return
        val pending = composing.toString()
        composing.setLength(0)
        val text = if (japaneseActive()) {
            val settled = KanaEngine.flush(pending)
            if (lang == Lang.KATAKANA) KanaEngine.toKatakana(settled) else settled
        } else pending
        currentInputConnection?.commitText(text, 1)
        showClipboardStrip()
    }

    /** Drop the buffer without typing anything (new field, or the keyboard was hidden). */
    private fun clearComposing() {
        if (composing.isEmpty()) return
        composing.setLength(0)
        currentInputConnection?.finishComposingText()
        showClipboardStrip()
    }

    /** Put the clipboard strip back in the top slot once we're done composing. */
    private fun showClipboardStrip() {
        if (!::topStrip.isInitialized) return
        setStrip(topStrip)
        topStrip.refresh(hideChips = passwordField)
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

    /** Long-pressing the globe opens the picker, so any of the five modes is one tap away rather
     *  than up to four long-presses of a cycle. */
    private fun showLanguagePicker() {
        setBody(
            LanguagePickerView(
                this, dark,
                current = lang,
                onPick = { selectLanguage(it) },
                onBack = { setBody(keyboardView) }
            )
        )
    }

    private fun selectLanguage(choice: Lang) {
        lang = choice
        // Read the pinyin dictionary the first time Chinese is chosen, so a session that never
        // uses it pays nothing.
        if (lang == Lang.PINYIN) PinyinEngine.ensureLoaded(this)
        mode = Mode.LETTERS
        applyLayout()   // also puts the key grid back as the visible body
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
        // A numeric / phone field gets a dedicated number pad, no letters to wade through.
        if (numPad != NumPad.NONE) {
            keyboardView.layout =
                if (numPad == NumPad.PHONE) Layouts.phonePad() else Layouts.numericPad()
            if (::root.isInitialized) setBody(keyboardView)
            return
        }
        // Narrow (1x) vs. wide (1.5x) modifier keys, per the user's setup-screen toggle.
        val mw = if (KeyboardPrefs.narrowModifiers(this)) Layouts.NARROW_MOD else Layouts.WIDE_MOD
        keyboardView.layout = when (mode) {
            Mode.SYMBOLS -> Layouts.symbols(mw)
            Mode.SYMBOLS2 -> Layouts.symbols2(mw)
            // Every mode but Russian types on the QWERTY letters (pinyin and romaji included);
            // only the key routing differs, so the layout choice is just Russian or not. The
            // space-bar label comes from the mode itself.
            Mode.LETTERS ->
                if (lang == Lang.RUSSIAN) Layouts.russian(mw, lang.label)
                else Layouts.english(mw, lang.label)
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
