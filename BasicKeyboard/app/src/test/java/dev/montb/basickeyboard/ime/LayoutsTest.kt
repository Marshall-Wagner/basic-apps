package dev.montb.basickeyboard.ime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the number-pad [Layout]s: pure data, no Android. They guard the promise that
 * these pads offer only numbers/symbols (no letters) and include the keys each field type needs.
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
    fun bothPadsAreFourKeysWidePerRow() {
        // Rows are all four keys, so the pads render as a square grid.
        assertTrue(Layouts.numericPad().rows.all { it.size == 4 })
        assertTrue(Layouts.phonePad().rows.all { it.size == 4 })
    }
}
