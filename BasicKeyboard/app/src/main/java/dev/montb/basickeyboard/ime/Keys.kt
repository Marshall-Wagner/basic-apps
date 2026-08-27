package dev.montb.basickeyboard.ime

/** What a key does when tapped. */
sealed interface KeyAction {
    /** Commit this text (a letter/symbol). [popup] = long-press alternatives. */
    data class Char(val text: String, val popup: List<String> = emptyList()) : KeyAction
    data object Shift : KeyAction
    data object Backspace : KeyAction
    data object Space : KeyAction
    data object Enter : KeyAction
    data object SymbolsLayer : KeyAction   // advance between symbol pages (page1 -> page2 -> page3 -> page1)
    data object NumbersToggle : KeyAction   // the 123/ABC key: just swap letters <-> numbers page (never pages 2/3)
    data object LetterLayer : KeyAction
    data object Language : KeyAction        // cycle language (globe key)
    data object Emoji : KeyAction
    data object Clipboard : KeyAction       // open the clipboard/paste panel
}

/** A single key: its action plus an optional display label override + width weight.
 *  [hint] is the tiny corner label (e.g. the number above q-w-e-…, or 📋/🌐).
 *  [longPressAction] fires on long-press instead of (or in addition to) text popups. */
data class Key(
    val action: KeyAction,
    val label: String? = null,
    val weight: Float = 1f,
    val hint: String? = null,
    val longPressAction: KeyAction? = null
)

/** A keyboard layout is rows of keys. */
data class Layout(val rows: List<List<Key>>)

/** Builds the concrete layouts. The top letter row shows tiny number hints (1-0) in
 *  the corner, Gboard-style, instead of a separate full-height number row. */
object Layouts {

    // The number each top-row key maps to (q=1 … p=0): shown as a corner hint and
    // available via long-press.
    private const val TOP_NUMBERS = "1234567890"

    // Long-press accents for English vowels/consonants.
    private val enPopups = mapOf(
        "a" to listOf("à", "á", "â", "ä", "ã", "å"),
        "e" to listOf("è", "é", "ê", "ë"),
        "i" to listOf("ì", "í", "î", "ï"),
        "o" to listOf("ò", "ó", "ô", "ö", "õ"),
        "u" to listOf("ù", "ú", "û", "ü"),
        "n" to listOf("ñ"),
        "c" to listOf("ç"),
        "y" to listOf("ÿ")
    )

    private fun letterRow(letters: String, popups: Map<String, List<String>> = emptyMap()): List<Key> =
        letters.map { c ->
            val s = c.toString()
            Key(KeyAction.Char(s, popups[s] ?: emptyList()))
        }

    /** Top row: each key shows its number in the corner; long-press types the number. */
    private fun topRow(letters: String, popups: Map<String, List<String>> = emptyMap()): List<Key> =
        letters.mapIndexed { i, c ->
            val s = c.toString()
            val num = TOP_NUMBERS.getOrNull(i)?.toString()
            val pop = listOfNotNull(num) + (popups[s] ?: emptyList())
            Key(KeyAction.Char(s, pop), hint = num)
        }

    // Width of the wide modifier keys (Shift/Backspace/123/=\</Enter). 1.5f = the
    // original wider keys; 1f = same as letters (the "smaller modifiers" setting).
    const val WIDE_MOD = 1.5f
    const val NARROW_MOD = 1f

    fun english(modWeight: Float = WIDE_MOD): Layout = Layout(
        listOf(
            topRow("qwertyuiop", enPopups),
            letterRow("asdfghjkl", enPopups),
            listOf(Key(KeyAction.Shift, "⇧", modWeight)) +
                letterRow("zxcvbnm", enPopups) +
                listOf(Key(KeyAction.Backspace, "⌫", modWeight)),
            bottomRow(modWeight)
        )
    )

    // Russian ЙЦУКЕН layout (top row has 11 keys; first 10 get number hints).
    fun russian(modWeight: Float = WIDE_MOD): Layout = Layout(
        listOf(
            topRow("йцукенгшщзх"),
            letterRow("фывапролджэ"),
            listOf(Key(KeyAction.Shift, "⇧", modWeight)) +
                letterRow("ячсмитьбю") +
                listOf(Key(KeyAction.Char("ё")), Key(KeyAction.Backspace, "⌫", modWeight)),
            bottomRow(modWeight)
        )
    )

    fun symbols(modWeight: Float = WIDE_MOD): Layout = Layout(
        listOf(
            letterRow("1234567890"),
            letterRow("@#\$_&-+()/"),
            // Row-3 left key advances to the extra symbols page (page 2 / "3rd page").
            listOf(Key(KeyAction.SymbolsLayer, "=\\<", modWeight)) +
                letterRow("*\"':;!?") +
                listOf(Key(KeyAction.Backspace, "⌫", modWeight)),
            bottomRow(modWeight, toggleLabel = "ABC")
        )
    )

    // Second symbols page (math/brackets).
    fun symbols2(modWeight: Float = WIDE_MOD): Layout = Layout(
        listOf(
            letterRow("~`|•√π÷×¶∆"),
            letterRow("£¢€¥^°={}\\"),
            // Row-3 left key cycles the symbol pages onward (back to page 1).
            listOf(Key(KeyAction.SymbolsLayer, "?123", modWeight)) +
                letterRow("%©®™✓[]") +
                listOf(Key(KeyAction.Backspace, "⌫", modWeight)),
            bottomRow(modWeight, toggleLabel = "ABC")
        )
    )

    // Bottom row matching the reference: [123/ABC] · , · emoji(🌐) · [space] · . · enter.
    // The leading key is a simple letters<->numbers toggle (NumbersToggle): it shows
    // "123" on the letter layouts and "ABC" on the symbol pages, and NEVER advances to
    // the extra symbol pages, that's the row-3 "=\<" / "?123" key's job.
    // Clipboard moves to a long-press of the comma key so we don't lose it. The globe
    // (language switch) lives on the emoji key's long-press too.
    private fun bottomRow(modWeight: Float = WIDE_MOD, toggleLabel: String = "123"): List<Key> = listOf(
        Key(KeyAction.NumbersToggle, toggleLabel, modWeight),
        // long-press , -> clipboard
        Key(KeyAction.Char(","), ",", 1f, hint = "📋", longPressAction = KeyAction.Clipboard),
        // long-press emoji -> switch language
        Key(KeyAction.Emoji, "☺", 1f, hint = "🌐", longPressAction = KeyAction.Language),
        Key(KeyAction.Space, "", 5f),
        Key(KeyAction.Char("."), ".", 1f),
        Key(KeyAction.Enter, "⏎", modWeight)
    )

    // A single number-pad key (a digit or a symbol). Like letterRow, the Char's own text
    // is its label, so there's nothing extra to set.
    private fun pad(s: String): Key = Key(KeyAction.Char(s))

    /** Number pad for numeric and date/time fields: the ten digits plus the separators a
     *  time or date needs (:, -, .), with backspace, enter, and an ABC key back to letters
     *  in case a field was mis-typed as numeric. No letter keys, this is what shows when a
     *  field only takes numbers. All rows are four keys wide so the grid stays square. */
    fun numericPad(): Layout = Layout(
        listOf(
            listOf(pad("1"), pad("2"), pad("3"), Key(KeyAction.Backspace, "⌫")),
            listOf(pad("4"), pad("5"), pad("6"), pad(":")),
            listOf(pad("7"), pad("8"), pad("9"), pad("-")),
            listOf(Key(KeyAction.LetterLayer, "ABC"), pad("0"), pad("."), Key(KeyAction.Enter, "⏎"))
        )
    )

    /** Dial pad for phone-number fields: 1-9, *, 0, #, plus + (international) and a comma
     *  pause, with backspace and enter. */
    fun phonePad(): Layout = Layout(
        listOf(
            listOf(pad("1"), pad("2"), pad("3"), Key(KeyAction.Backspace, "⌫")),
            listOf(pad("4"), pad("5"), pad("6"), pad("+")),
            listOf(pad("7"), pad("8"), pad("9"), pad(",")),
            listOf(pad("*"), pad("0"), pad("#"), Key(KeyAction.Enter, "⏎"))
        )
    )
}
