package dev.montb.basickeyboard.ime

/**
 * Deterministic romaji -> kana conversion (hiragana and katakana). No kanji.
 *
 * Unlike pinyin this needs no dictionary at all: romaji maps onto kana by a fixed table, so the
 * conversion is exact and the whole thing is a few hundred bytes of code. Kanji conversion is the
 * genuinely hard half of Japanese input (it needs morphological analysis plus a trained language
 * model) and is deliberately NOT attempted here.
 *
 * Only hiragana is tabulated; katakana comes from the fixed +0x60 Unicode offset between the two
 * blocks, so there is one table to keep correct rather than two.
 *
 * Handles the three things a naive table misses:
 *  - sokuon (doubled consonant):  "tta" -> った
 *  - syllabic n before a consonant: "kanji" -> かんじ, and "nn" -> ん
 *  - incomplete tails: "ky" converts to nothing and stays pending until the vowel arrives.
 */
object KanaEngine {

    private const val SMALL_TSU = "っ"
    private const val SYLLABIC_N = "ん"
    private val VOWELS = setOf('a', 'i', 'u', 'e', 'o')
    /** Longest table key ("xtsu"). */
    private const val MAX_KEY = 4

    private val table: Map<String, String> = mapOf(
        "a" to "あ", "i" to "い", "u" to "う", "e" to "え", "o" to "お",
        "ka" to "か", "ki" to "き", "ku" to "く", "ke" to "け", "ko" to "こ",
        "ga" to "が", "gi" to "ぎ", "gu" to "ぐ", "ge" to "げ", "go" to "ご",
        "sa" to "さ", "shi" to "し", "si" to "し", "su" to "す", "se" to "せ", "so" to "そ",
        "za" to "ざ", "ji" to "じ", "zi" to "じ", "zu" to "ず", "ze" to "ぜ", "zo" to "ぞ",
        "ta" to "た", "chi" to "ち", "ti" to "ち", "tsu" to "つ", "tu" to "つ",
        "te" to "て", "to" to "と",
        "da" to "だ", "di" to "ぢ", "du" to "づ", "de" to "で", "do" to "ど",
        // NB: "nn" is deliberately NOT here. It is ambiguous and is resolved by rule below.
        "na" to "な", "ni" to "に", "nu" to "ぬ", "ne" to "ね", "no" to "の",
        "ha" to "は", "hi" to "ひ", "fu" to "ふ", "hu" to "ふ", "he" to "へ", "ho" to "ほ",
        "ba" to "ば", "bi" to "び", "bu" to "ぶ", "be" to "べ", "bo" to "ぼ",
        "pa" to "ぱ", "pi" to "ぴ", "pu" to "ぷ", "pe" to "ぺ", "po" to "ぽ",
        "ma" to "ま", "mi" to "み", "mu" to "む", "me" to "め", "mo" to "も",
        "ya" to "や", "yu" to "ゆ", "yo" to "よ",
        "ra" to "ら", "ri" to "り", "ru" to "る", "re" to "れ", "ro" to "ろ",
        "wa" to "わ", "wo" to "を",
        // y-digraphs, with the common alternate spellings people actually type
        "kya" to "きゃ", "kyu" to "きゅ", "kyo" to "きょ",
        "gya" to "ぎゃ", "gyu" to "ぎゅ", "gyo" to "ぎょ",
        "sha" to "しゃ", "shu" to "しゅ", "sho" to "しょ",
        "sya" to "しゃ", "syu" to "しゅ", "syo" to "しょ",
        "ja" to "じゃ", "ju" to "じゅ", "jo" to "じょ",
        "jya" to "じゃ", "jyu" to "じゅ", "jyo" to "じょ",
        "zya" to "じゃ", "zyu" to "じゅ", "zyo" to "じょ",
        "cha" to "ちゃ", "chu" to "ちゅ", "cho" to "ちょ",
        "cya" to "ちゃ", "cyu" to "ちゅ", "cyo" to "ちょ",
        "tya" to "ちゃ", "tyu" to "ちゅ", "tyo" to "ちょ",
        "nya" to "にゃ", "nyu" to "にゅ", "nyo" to "にょ",
        "hya" to "ひゃ", "hyu" to "ひゅ", "hyo" to "ひょ",
        "bya" to "びゃ", "byu" to "びゅ", "byo" to "びょ",
        "pya" to "ぴゃ", "pyu" to "ぴゅ", "pyo" to "ぴょ",
        "mya" to "みゃ", "myu" to "みゅ", "myo" to "みょ",
        "rya" to "りゃ", "ryu" to "りゅ", "ryo" to "りょ",
        // loanword syllables (mostly used in katakana)
        "fa" to "ふぁ", "fi" to "ふぃ", "fe" to "ふぇ", "fo" to "ふぉ",
        "she" to "しぇ", "je" to "じぇ", "che" to "ちぇ",
        "wi" to "うぃ", "we" to "うぇ",
        // small kana and the long-vowel mark
        "xa" to "ぁ", "xi" to "ぃ", "xu" to "ぅ", "xe" to "ぇ", "xo" to "ぉ",
        "xtsu" to "っ", "xtu" to "っ",
        "-" to "ー"
    )

    /**
     * Convert as much of [romaji] as possible. Returns the kana produced plus the leftover that is
     * still an incomplete syllable and should stay pending (e.g. "ky" waiting for its vowel).
     */
    fun convert(romaji: String): Pair<String, String> {
        val out = StringBuilder()
        var i = 0
        outer@ while (i < romaji.length) {
            var len = minOf(MAX_KEY, romaji.length - i)
            while (len >= 1) {
                val kana = table[romaji.substring(i, i + len)]
                if (kana != null) {
                    out.append(kana)
                    i += len
                    continue@outer
                }
                len--
            }
            val c = romaji[i]
            val next = if (i + 1 < romaji.length) romaji[i + 1] else null
            // Doubled consonant is the sokuon: "tta" -> った. 'n' is excluded, handled below.
            if (next != null && c == next && c !in VOWELS && c != 'n' && c.isLetter()) {
                out.append(SMALL_TSU)
                i++
                continue
            }
            // "nn" is ambiguous and cannot be a plain table entry. On its own it is ん, but in
            // "nni" the second n starts the な-row, so it has to split as ん + に
            // ("konnichiwa" -> こんにちわ, NOT こんいちわ). Look past the pair to decide, and hold
            // it pending while the answer is still unknown.
            if (c == 'n' && next == 'n') {
                val after = if (i + 2 < romaji.length) romaji[i + 2] else null
                when {
                    after == null -> break@outer                            // wait for the next key
                    after in VOWELS || after == 'y' -> { out.append(SYLLABIC_N); i++ }
                    else -> { out.append(SYLLABIC_N); i += 2 }
                }
                continue
            }
            // A lone 'n' before another consonant is syllabic ん: "kanji" -> かんじ.
            if (c == 'n' && next != null && next !in VOWELS && next != 'y' && next != 'n') {
                out.append(SYLLABIC_N)
                i++
                continue
            }
            // Nothing convertible yet; everything from here is an incomplete syllable.
            break
        }
        return out.toString() to romaji.substring(i)
    }

    /** What a pending tail becomes when input ends: "n" and "nn" both settle to ん (the latter is
     *  held pending by [convert] until it is clear it isn't ん + な-row); anything else stays literal. */
    fun flush(pending: String): String =
        if (pending == "n" || pending == "nn") SYLLABIC_N else pending

    /** Hiragana U+3041..U+3096 map onto katakana by a fixed +0x60 offset; anything else passes through. */
    fun toKatakana(hiragana: String): String = buildString {
        for (c in hiragana) append(if (c in 'ぁ'..'ゖ') c + 0x60 else c)
    }
}
