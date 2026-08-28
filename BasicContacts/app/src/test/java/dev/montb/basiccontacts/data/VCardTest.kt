package dev.montb.basiccontacts.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for VCard's pure parse/escape helpers. No Android: the tests avoid the photo
 * path (the one place that touches android.util.Base64), so everything runs on the plain JVM.
 */
class VCardTest {

    @Test
    fun unfoldRejoinsFoldedContinuationLines() {
        // A value split across lines with a folded continuation (CRLF + a leading space) rejoins.
        assertEquals("EMAIL:alice@example.com", VCard.unfold("EMAIL:alice@exam\r\n ple.com"))
    }

    @Test
    fun unfoldNormalizesLineEndings() {
        assertEquals("A\nB", VCard.unfold("A\r\nB"))
    }

    @Test
    fun splitCardsReturnsEachCardBetweenBeginAndEnd() {
        val text = "BEGIN:VCARD\nFN:Alice\nEND:VCARD\nBEGIN:VCARD\nFN:Bob\nEND:VCARD\n"
        val cards = VCard.splitCards(text)
        assertEquals(2, cards.size)
        assertTrue(cards[0].contains("FN:Alice"))
        assertTrue(cards[1].contains("FN:Bob"))
    }

    @Test
    fun splitCardsIgnoresTextOutsideACard() {
        val text = "junk\nBEGIN:VCARD\nFN:Alice\nEND:VCARD\ntrailing junk\n"
        val cards = VCard.splitCards(text)
        assertEquals(1, cards.size)
        assertTrue(cards[0].contains("FN:Alice"))
    }

    @Test
    fun labelFromReadsTheTypeParameter() {
        assertEquals("Mobile", VCard.labelFrom("TEL;TYPE=CELL", "Home"))
        assertEquals("Work", VCard.labelFrom("TEL;TYPE=work", "Home"))
    }

    @Test
    fun labelFromFallsBackWhenNoTypePresent() {
        assertEquals("Home", VCard.labelFrom("TEL", "Home"))
    }

    @Test
    fun typeParamMapsLabelsToVcardTypes() {
        assertEquals("CELL", VCard.typeParam("Mobile"))
        assertEquals("WORK", VCard.typeParam("Work"))
        assertEquals("VOICE", VCard.typeParam("Other")) // unknown label -> generic VOICE
    }

    @Test
    fun escapeThenDecodeRoundTripsCommaSemicolonNewline() {
        val original = "a,b;c\nd"
        val escaped = VCard.escape(original)
        assertEquals("a\\,b\\;c\\nd", escaped)
        assertEquals(original, VCard.decode(escaped))
    }

    @Test
    fun escapeDoublesBackslashes() {
        assertEquals("a\\\\b", VCard.escape("a\\b"))
    }
}
