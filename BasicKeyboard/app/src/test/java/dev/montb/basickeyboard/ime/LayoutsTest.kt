package dev.montb.basickeyboard.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the [Layout]s: pure data, no Android. They guard the promise that the number pads
 * offer only numbers/symbols (no letters) and include the keys each field type needs, and that the
 * Korean layout can type every jamo Hangul composition needs.
 */
class LayoutsTest {

    /** The committed text of every Char key across all rows of a layout. */
    private fun chars(layout: Layout): List<String> =
        layout.rows.flatten().mapNotNull { (it.action as? KeyAction.Char)?.text }

    /** Every key action across all rows of a layout. */
    private fun actions(layout: Layout): List<KeyAction> =
        layout.rows.flatten().map { it.action }

    @Test
    fun numericPadHasEveryDigit() {
        val c = chars(Layouts.numericPad())
        for (d in '0'..'9') assertTrue("numeric pad missing digit $d", c.contains(d.toString()))
    }

    @Test
    fun numericPadHasTimeAndDateSeparators() {
        assertTrue(chars(Layouts.numericPad()).containsAll(listOf(":", "-", ".")))
    }

    @Test
    fun numericPadHasNoLetters() {
        assertFalse(chars(Layouts.numericPad()).any { it.length == 1 && it[0].isLetter() })
    }

    @Test
    fun numericPadHasBackspaceEnterAndLetterEscape() {
        val a = actions(Layouts.numericPad())
        assertTrue(a.contains(KeyAction.Backspace))
        assertTrue(a.contains(KeyAction.Enter))
        assertTrue(a.contains(KeyAction.LetterLayer)) // the ABC key back to letters
    }

    @Test
    fun phonePadHasEveryDigit() {
        val c = chars(Layouts.phonePad())
        for (d in '0'..'9') assertTrue("phone pad missing digit $d", c.contains(d.toString()))
    }

    @Test
    fun phonePadHasDialSymbols() {
        assertTrue(chars(Layouts.phonePad()).containsAll(listOf("*", "#", "+", ",")))
    }

    @Test
    fun phonePadHasNoLetters() {
        assertFalse(chars(Layouts.phonePad()).any { it.length == 1 && it[0].isLetter() })
    }

    @Test
    fun koreanLayoutTypesOnlyJamo() {
        // Every letter key must feed the composer, so a stray latin key would type raw text into
        // the middle of a Korean word.
        val letters = Layouts.korean().rows.dropLast(1).flatten()
            .mapNotNull { (it.action as? KeyAction.Char)?.text }
        assertTrue(letters.isNotEmpty())
        assertTrue(
            "non-jamo key on the Korean layout: " +
                letters.filterNot { it.length == 1 && HangulEngine.isJamo(it[0]) },
            letters.all { it.length == 1 && HangulEngine.isJamo(it[0]) }
        )
    }

    @Test
    fun koreanLayoutHasEveryJamoNeededToTypeHangul() {
        val keys = Layouts.korean().rows.flatten().mapNotNull { it.action as? KeyAction.Char }
        // Unshifted plus shifted forms together are the full 2-set set: 14 plain consonants,
        // 5 tense ones, and 10 vowels, which compose everything else.
        val typeable = keys.map { it.text } + keys.mapNotNull { it.shiftText }
        for (jamo in "ㄱㄴㄷㄹㅁㅂㅅㅇㅈㅊㅋㅌㅍㅎㄲㄸㅃㅆㅉㅏㅐㅑㅒㅓㅔㅕㅖㅗㅛㅜㅠㅡㅣ") {
            assertTrue("Korean layout cannot type $jamo", typeable.contains(jamo.toString()))
        }
    }

    @Test
    fun koreanShiftGivesTenseConsonantsNotUppercase() {
        val shifts = Layouts.korean().rows.flatten()
            .mapNotNull { it.action as? KeyAction.Char }
            .filter { it.shiftText != null }
            .associate { it.text to it.shiftText }
        assertEquals(mapOf(
            "ㅂ" to "ㅃ", "ㅈ" to "ㅉ", "ㄷ" to "ㄸ", "ㄱ" to "ㄲ", "ㅅ" to "ㅆ",
            "ㅐ" to "ㅒ", "ㅔ" to "ㅖ"
        ), shifts)
    }

    @Test
    fun koreanLayoutMatchesTheQwertyKeyShape() {
        // 2-set sits on the QWERTY positions, so the rows are the same widths as english().
        assertEquals(
            Layouts.english().rows.map { it.size },
            Layouts.korean().rows.map { it.size }
        )
    }

    @Test
    fun latinLayoutsHaveNoExplicitShiftForms() {
        // Latin and Cyrillic shift by uppercasing; only Hangul needs the explicit second form.
        for (layout in listOf(Layouts.english(), Layouts.russian())) {
            assertTrue(layout.rows.flatten()
                .mapNotNull { it.action as? KeyAction.Char }
                .all { it.shiftText == null })
        }
    }

    @Test
    fun bothPadsAreFourKeysWidePerRow() {
        // Rows are all four keys, so the pads render as a square grid.
        assertTrue(Layouts.numericPad().rows.all { it.size == 4 })
        assertTrue(Layouts.phonePad().rows.all { it.size == 4 })
    }
}
