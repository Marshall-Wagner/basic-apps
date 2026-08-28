package dev.montb.basicphone

import dev.montb.basicphone.data.SpamHint
import dev.montb.basicphone.util.Voicemail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for BasicPhone's pure helpers: voicemail-number matching (digits only, so
 * formatting differences don't matter) and the offline spam-hint labels. No Android, no
 * Telephony, no Context.
 */
class PhoneLogicTest {

    // --- Voicemail.isVoicemail ---

    @Test
    fun matchesWhenDigitsMatchDespiteFormatting() {
        val known = setOf("15551234567")
        assertTrue(Voicemail.isVoicemail("+1 (555) 123-4567", known))
    }

    @Test
    fun matchesPlainDigits() {
        assertTrue(Voicemail.isVoicemail("123", setOf("123")))
    }

    @Test
    fun doesNotMatchAnUnknownNumber() {
        assertFalse(Voicemail.isVoicemail("5559999999", setOf("15551234567")))
    }

    @Test
    fun emptyOrPunctuationOnlyNumberNeverMatches() {
        val known = setOf("15551234567")
        assertFalse(Voicemail.isVoicemail("", known))
        assertFalse(Voicemail.isVoicemail("+++", known))
    }

    @Test
    fun noKnownNumbersMeansNoMatch() {
        assertFalse(Voicemail.isVoicemail("123", emptySet()))
    }

    // --- SpamHint.label ---

    @Test
    fun spamHintLabelsMapCorrectly() {
        assertNull(SpamHint.NONE.label)
        assertEquals("⚠ Hidden caller", SpamHint.WITHHELD.label)
        assertEquals("⚠ Possible spam", SpamHint.REPEATED.label)
    }
}
