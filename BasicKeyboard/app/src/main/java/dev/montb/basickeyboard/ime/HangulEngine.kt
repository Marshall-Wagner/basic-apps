package dev.montb.basickeyboard.ime

/**
 * Hangul composition: jamo in, syllable blocks out. No dictionary, no candidates, no guessing.
 *
 * Korean is the one CJK script whose input is pure arithmetic. A Hangul syllable block is
 *
 *     block = 0xAC00 + (initial * 21 + medial) * 28 + final
 *
 * over 19 initials, 21 medials and 28 finals (index 0 = no final). That formula is the entire
 * conversion, which is why this ships no data file and can never rank a wrong answer first the way
 * pinyin can. Hanja conversion IS dictionary-based and is deliberately not attempted here, for the
 * same reason kanji isn't in [KanaEngine].
 *
 * The buffer is compatibility jamo (U+3131..U+3163), which is exactly what the 2-set key labels
 * are, so a keypress appends the character printed on the key and nothing needs translating first.
 *
 * Three things a naive "append the jamo and precompose" version gets wrong, all handled below:
 *  - compound medials: ㅗ + ㅏ is the single vowel ㅘ, not two vowels
 *  - compound finals: ㅂ + ㅅ is the single final ㅄ
 *  - the final detaches when a vowel follows it: ㅎㅏㄴ + ㅣ is 하니, not 한ㅣ. This is the direct
 *    counterpart of the syllabic-n lookahead in [KanaEngine], and it is the case whose absence
 *    makes Korean input feel broken rather than merely limited.
 */
object HangulEngine {

    private const val BASE = 0xAC00
    private const val MEDIALS = 21
    private const val FINALS = 28
    private const val BLOCKS = 19 * MEDIALS * FINALS

    // Unicode's own orderings. The index of a jamo in these strings IS its index in the formula,
    // so the tables are the spec rather than a choice we made.
    private const val CHO = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ"
    private const val JUNG = "ㅏㅐㅑㅒㅓㅔㅕㅖㅗㅘㅙㅚㅛㅜㅝㅞㅟㅠㅡㅢㅣ"
    // Final index 0 means "no final", so this string holds finals 1..27 and is looked up with an
    // off-by-one. ㄸ, ㅃ and ㅉ are absent: they can only ever be initials.
    private const val JONG = "ㄱㄲㄳㄴㄵㄶㄷㄹㄺㄻㄼㄽㄾㄿㅀㅁㅂㅄㅅㅆㅇㅈㅊㅋㅌㅍㅎ"

    private val COMPOUND_MEDIAL = mapOf(
        "ㅗㅏ" to 'ㅘ', "ㅗㅐ" to 'ㅙ', "ㅗㅣ" to 'ㅚ',
        "ㅜㅓ" to 'ㅝ', "ㅜㅔ" to 'ㅞ', "ㅜㅣ" to 'ㅟ',
        "ㅡㅣ" to 'ㅢ'
    )

    private val COMPOUND_FINAL = mapOf(
        "ㄱㅅ" to 'ㄳ',
        "ㄴㅈ" to 'ㄵ', "ㄴㅎ" to 'ㄶ',
        "ㄹㄱ" to 'ㄺ', "ㄹㅁ" to 'ㄻ', "ㄹㅂ" to 'ㄼ', "ㄹㅅ" to 'ㄽ',
        "ㄹㅌ" to 'ㄾ', "ㄹㅍ" to 'ㄿ', "ㄹㅎ" to 'ㅀ',
        "ㅂㅅ" to 'ㅄ'
    )

    /** Every compound back to the two jamo that formed it, for the detach rule and for backspace. */
    private val SPLIT: Map<Char, String> =
        (COMPOUND_MEDIAL + COMPOUND_FINAL).entries.associate { it.value to it.key }

    /** A block plus how many jamo of the input it consumed, so a caller can tell settled from live. */
    internal data class Block(val text: String, val jamoCount: Int)

    fun isJamo(c: Char): Boolean =
        CHO.indexOf(c) >= 0 || JUNG.indexOf(c) >= 0 || JONG.indexOf(c) >= 0

    /** True for a precomposed syllable (U+AC00..U+D7A3), the thing [decompose] can take apart. */
    fun isSyllable(c: Char): Boolean = c.code in BASE until BASE + BLOCKS

    /** Final index for [c], or 0 when [c] can never be a final (ㄸ/ㅃ/ㅉ, or a vowel). */
    private fun jongIndex(c: Char): Int = JONG.indexOf(c) + 1

    private fun render(cho: Int, jung: Int, jong: Int): String = when {
        cho >= 0 && jung >= 0 -> (BASE + (cho * MEDIALS + jung) * FINALS + jong).toChar().toString()
        // A half-typed block shows the bare jamo, which is what the user has actually typed so far.
        cho >= 0 -> CHO[cho].toString()
        jung >= 0 -> JUNG[jung].toString()
        else -> ""
    }

    /**
     * Run the composition automaton over [jamos]. Every input character is accounted for in
     * exactly one returned block (non-jamo passes through as its own block), so the blocks' texts
     * concatenate to the full output and their [Block.jamoCount]s sum to `jamos.length`.
     */
    internal fun blocks(jamos: String): List<Block> {
        val out = ArrayList<Block>()
        var cho = -1
        var jung = -1
        var jong = 0
        var count = 0   // jamo consumed by the block currently being built

        // Close off the block in progress, crediting it [owned] jamo of the input, and reset.
        fun emit(owned: Int) {
            if (owned > 0) out.add(Block(render(cho, jung, jong), owned))
            cho = -1; jung = -1; jong = 0; count = 0
        }

        // Begin a new block on a consonant. A compound-only jamo (ㄳ, ㅄ, ...) cannot start one;
        // it is not reachable from the 2-set keyboard, so this just keeps the function total
        // rather than silently dropping the character.
        fun startBlock(asCho: Int, raw: Char) {
            emit(count)
            if (asCho >= 0) { cho = asCho; count = 1 } else out.add(Block(raw.toString(), 1))
        }

        for (ch in jamos) {
            val asJung = JUNG.indexOf(ch)
            if (asJung >= 0) {
                when {
                    // First vowel of the block, whether or not an initial is already waiting.
                    jung < 0 -> { jung = asJung; count++ }
                    // Second vowel, no final yet: only a legal pair merges (ㅗ + ㅏ -> ㅘ).
                    jong == 0 -> {
                        val merged = COMPOUND_MEDIAL[JUNG[jung].toString() + ch]
                        if (merged != null) { jung = JUNG.indexOf(merged); count++ }
                        else { emit(count); jung = asJung; count = 1 }
                    }
                    // The detach rule: a vowel after a final means that final was never a final,
                    // it is the next block's initial. ㅎㅏㄴ + ㅣ is 하 + 니. A compound final
                    // splits, keeping its first half behind: ㄱㅏㅂㅅ + ㅣ is 갑 + 시.
                    else -> {
                        val finalJamo = JONG[jong - 1]
                        val halves = SPLIT[finalJamo]
                        val moved: Char
                        if (halves != null) {
                            jong = jongIndex(halves[0])
                            moved = halves[1]
                        } else {
                            jong = 0
                            moved = finalJamo
                        }
                        emit(count - 1)   // the moved consonant belongs to the next block now
                        cho = CHO.indexOf(moved); jung = asJung; count = 2
                    }
                }
                continue
            }

            val asCho = CHO.indexOf(ch)
            val asJong = jongIndex(ch)
            if (asCho < 0 && asJong == 0) {
                // Not a jamo at all (punctuation pasted into the buffer, say). Pass it through so
                // nothing is ever swallowed.
                emit(count)
                out.add(Block(ch.toString(), 1))
                continue
            }
            when {
                // No medial yet, or a bare vowel standing alone: this consonant starts a block.
                jung < 0 || cho < 0 -> startBlock(asCho, ch)
                // Initial + medial present, so try this as the block's final.
                jong == 0 -> if (asJong > 0) { jong = asJong; count++ } else startBlock(asCho, ch)
                // A final already: only a legal compound may join it.
                else -> {
                    val merged = COMPOUND_FINAL[JONG[jong - 1].toString() + ch]
                    if (merged != null) { jong = jongIndex(merged); count++ }
                    else startBlock(asCho, ch)
                }
            }
        }
        emit(count)
        return out
    }

    /** Compose a whole jamo run into finished text. */
    fun compose(jamos: String): String = blocks(jamos).joinToString("") { it.text }

    /**
     * Split [jamos] into the part that can no longer change and the jamo still belonging to the
     * live block. Only the last block is ever mutable: once a following block has started, the
     * detach rule can no longer reach back into the previous one.
     *
     * Shaped like [KanaEngine.convert] so the service handles both the same way, but note the
     * difference: kana's second element is leftover that could not convert, while this one is
     * fully renderable already (via [compose]) and is held only because the next keypress may
     * still rewrite it.
     */
    fun convert(jamos: String): Pair<String, String> {
        val bs = blocks(jamos)
        if (bs.size <= 1) return "" to jamos
        val live = bs.last().jamoCount
        return bs.dropLast(1).joinToString("") { it.text } to jamos.takeLast(live)
    }

    /**
     * A precomposed syllable back into its jamo, with compounds expanded (값 -> ㄱㅏㅂㅅ, 화 ->
     * ㅎㅗㅏ). Dropping the last jamo of the result and recomposing is what makes backspace peel a
     * block apart one keypress at a time instead of deleting the whole syllable.
     */
    fun decompose(syllable: Char): String {
        if (!isSyllable(syllable)) return syllable.toString()
        val offset = syllable.code - BASE
        val jong = offset % FINALS
        val jung = (offset / FINALS) % MEDIALS
        val cho = offset / (FINALS * MEDIALS)
        return buildString {
            append(CHO[cho])
            append(expand(JUNG[jung]))
            if (jong > 0) append(expand(JONG[jong - 1]))
        }
    }

    private fun expand(jamo: Char): String = SPLIT[jamo] ?: jamo.toString()
}
