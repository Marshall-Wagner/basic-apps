package dev.montb.basickeyboard.ime

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for romaji -> kana. Pure logic, no Android. Covers the three cases a naive lookup
 * table gets wrong: the sokuon, syllabic n, and incomplete syllable tails.
 */
class KanaEngineTest {

    private fun kana(romaji: String) = KanaEngine.convert(romaji).first
    private fun pending(romaji: String) = KanaEngine.convert(romaji).second

    @Test
    fun basicSyllables() {
        assertEquals("か", kana("ka"))
        assertEquals("あいうえお", kana("aiueo"))
        assertEquals("さくら", kana("sakura"))
    }

    @Test
    fun digraphsAndAlternateSpellings() {
        assertEquals("きゃ", kana("kya"))
        assertEquals("しゃ", kana("sha"))
        assertEquals("ち", kana("chi"))
        assertEquals("つ", kana("tsu"))
        // "shi"/"si" and "ji"/"zi" are both accepted spellings of the same kana.
        assertEquals(kana("shi"), kana("si"))
        assertEquals(kana("ji"), kana("zi"))
    }

    @Test
    fun doubledConsonantBecomesSokuon() {
        assertEquals("った", kana("tta"))
        assertEquals("っか", kana("kka"))
        assertEquals("がっこう", kana("gakkou"))
    }

    @Test
    fun loneNBeforeAConsonantIsSyllabicN() {
        assertEquals("かんじ", kana("kanji"))
        assertEquals("げんき", kana("genki"))
    }

    @Test
    fun doubleNIsSyllabicN() {
        assertEquals("ん", kana("nn"))
        assertEquals("こんにちわ", kana("konnichiwa"))
    }

    @Test
    fun nBeforeAVowelOrYStaysASyllable() {
        assertEquals("な", kana("na"))
        assertEquals("にゃ", kana("nya"))
    }

    @Test
    fun incompleteSyllablesStayPending() {
        assertEquals("", kana("k"))
        assertEquals("k", pending("k"))
        assertEquals("", kana("ky"))
        assertEquals("ky", pending("ky"))
        // A lone "n" is ambiguous (na/ni/ん), so it waits rather than committing early.
        assertEquals("", kana("n"))
        assertEquals("n", pending("n"))
    }

    @Test
    fun completedSyllablesEmitAndOnlyTheTailRemains() {
        assertEquals("か", kana("kak"))
        assertEquals("k", pending("kak"))
    }

    @Test
    fun flushResolvesATrailingN() {
        assertEquals("ん", KanaEngine.flush("n"))
        // Anything else that could not convert is left literal rather than silently dropped.
        assertEquals("ky", KanaEngine.flush("ky"))
        assertEquals("", KanaEngine.flush(""))
    }

    @Test
    fun katakanaIsTheSameTableShifted() {
        assertEquals("カタカナ", KanaEngine.toKatakana(kana("katakana")))
        assertEquals("ッタ", KanaEngine.toKatakana(kana("tta")))
        assertEquals("キャ", KanaEngine.toKatakana(kana("kya")))
        // The long-vowel mark is shared between the scripts, so it passes through unchanged.
        assertEquals("ー", KanaEngine.toKatakana("ー"))
    }
}
