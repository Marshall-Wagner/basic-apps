package dev.montb.basickeyboard.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for jamo -> Hangul composition. Pure logic, no Android. Covers the three cases a
 * naive "append the jamo and precompose" version gets wrong (compound medials, compound finals,
 * and the final detaching before a vowel) plus the invariants the IME relies on.
 */
class HangulEngineTest {

    private fun compose(jamos: String) = HangulEngine.compose(jamos)

    @Test
    fun buildsBasicBlocks() {
        assertEquals("하", compose("ㅎㅏ"))
        assertEquals("한", compose("ㅎㅏㄴ"))
        assertEquals("감", compose("ㄱㅏㅁ"))
    }

    @Test
    fun halfTypedBlockShowsTheBareJamo() {
        // What the user has actually typed so far, rather than nothing at all.
        assertEquals("ㄱ", compose("ㄱ"))
        assertEquals("ㅏ", compose("ㅏ"))
    }

    @Test
    fun compoundMedialsMerge() {
        assertEquals("화", compose("ㅎㅗㅏ"))   // ㅗ + ㅏ is the single vowel ㅘ
        assertEquals("의", compose("ㅇㅡㅣ"))   // ㅡ + ㅣ is ㅢ
        assertEquals("웠", compose("ㅇㅜㅓㅆ"))  // ㅜ + ㅓ is ㅝ, then a tense final
        // A compound vowel still merges with no initial in front of it.
        assertEquals("ㅘ", compose("ㅗㅏ"))
    }

    @Test
    fun compoundFinalsMerge() {
        assertEquals("값", compose("ㄱㅏㅂㅅ"))   // ㅂ + ㅅ is the single final ㅄ
        assertEquals("읽", compose("ㅇㅣㄹㄱ"))   // ㄹ + ㄱ is ㄺ
    }

    @Test
    fun finalDetachesWhenAVowelFollows() {
        // The case that matters: a vowel after a final means that consonant was never a final,
        // it starts the next block. 하니, not 한ㅣ.
        assertEquals("하니", compose("ㅎㅏㄴㅣ"))
        assertEquals("한글", compose("ㅎㅏㄴㄱㅡㄹ"))
        assertEquals("한국어", compose("ㅎㅏㄴㄱㅜㄱㅇㅓ"))
    }

    @Test
    fun detachSplitsACompoundFinalInHalf() {
        // ㅄ splits: ㅂ stays behind as 갑's final, ㅅ moves on to start 시.
        assertEquals("갑시", compose("ㄱㅏㅂㅅㅣ"))
        assertEquals("없어", compose("ㅇㅓㅂㅅㅇㅓ"))
    }

    @Test
    fun consonantAfterAFinalDoesNotDetach() {
        // Only a vowel triggers the detach; a consonant just starts a new block.
        assertEquals("한ㄱ", compose("ㅎㅏㄴㄱ"))
    }

    @Test
    fun tenseConsonantsThatCannotBeFinalsStartANewBlock() {
        // ㄸ, ㅃ and ㅉ are initials only, so they cannot be picked up as a final.
        assertEquals("가ㄸ", compose("ㄱㅏㄸ"))
        assertEquals("가ㅃ", compose("ㄱㅏㅃ"))
        // ㄷ, which looks similar, genuinely can be a final.
        assertEquals("갇", compose("ㄱㅏㄷ"))
    }

    @Test
    fun composesRealWords() {
        assertEquals("안녕하세요", compose("ㅇㅏㄴㄴㅕㅇㅎㅏㅅㅔㅇㅛ"))
        assertEquals("감사합니다", compose("ㄱㅏㅁㅅㅏㅎㅏㅂㄴㅣㄷㅏ"))
        assertEquals("서울", compose("ㅅㅓㅇㅜㄹ"))
        assertEquals("꽃", compose("ㄲㅗㅊ"))
    }

    @Test
    fun convertSeparatesSettledTextFromTheLiveBlock() {
        // One block: nothing has settled, it can all still change.
        assertEquals("" to "ㅎㅏㄴ", HangulEngine.convert("ㅎㅏㄴ"))
        // A second block started, so the first can never change again.
        assertEquals("한" to "ㄱ", HangulEngine.convert("ㅎㅏㄴㄱ"))
        // After a detach the settled half is 하, and ㄴ has moved into the live block.
        assertEquals("하" to "ㄴㅣ", HangulEngine.convert("ㅎㅏㄴㅣ"))
        assertEquals("갑" to "ㅅㅣ", HangulEngine.convert("ㄱㅏㅂㅅㅣ"))
        assertEquals("" to "", HangulEngine.convert(""))
    }

    @Test
    fun committingTheSettledHalfAndKeepingTheRestPreservesTheText() {
        // This is exactly what the service does each keypress, so the two halves must reassemble.
        for (jamos in listOf("ㅎㅏㄴㅣ", "ㄱㅏㅂㅅㅣ", "ㅎㅏㄴㄱㅡㄹ", "ㅇㅏㄴㄴㅕㅇ", "ㄲㅗㅊ")) {
            val (settled, live) = HangulEngine.convert(jamos)
            assertEquals(jamos, compose(jamos), settled + compose(live))
        }
    }

    @Test
    fun decomposeExpandsCompoundsSoBackspacePeelsOneKeypress() {
        assertEquals("ㅎㅏㄴ", HangulEngine.decompose('한'))
        assertEquals("ㄱㅏㅂㅅ", HangulEngine.decompose('값'))   // ㅄ back into ㅂ + ㅅ
        assertEquals("ㅎㅗㅏ", HangulEngine.decompose('화'))     // ㅘ back into ㅗ + ㅏ
        // Dropping the last jamo is one backspace, so each of these is the previous block.
        assertEquals("하", compose(HangulEngine.decompose('한').dropLast(1)))
        assertEquals("갑", compose(HangulEngine.decompose('값').dropLast(1)))
        assertEquals("호", compose(HangulEngine.decompose('화').dropLast(1)))
    }

    @Test
    fun decomposeAndComposeRoundTripEverySyllable() {
        // All 11,172 precomposed blocks, so the tables and the formula cannot drift apart.
        for (code in 0xAC00..0xD7A3) {
            val syllable = code.toChar()
            assertEquals(
                "round-trip failed for $syllable",
                syllable.toString(),
                compose(HangulEngine.decompose(syllable))
            )
        }
    }

    @Test
    fun nonJamoPassesThroughInsteadOfBeingSwallowed() {
        assertEquals("하!", compose("ㅎㅏ!"))
        assertEquals("x", HangulEngine.decompose('x'))
    }

    @Test
    fun everyJamoIsAccountedForInExactlyOneBlock() {
        // The service trusts the counts to decide how much of the buffer to keep, so a block that
        // over- or under-claims would lose or duplicate the user's keystrokes.
        for (jamos in listOf("ㅎㅏㄴㅣ", "ㄱㅏㅂㅅㅣ", "ㅎㅏㄴㄱㅡㄹ", "ㅗㅏ", "ㄱㅏㄸ", "ㅎㅏ!")) {
            assertEquals(
                "jamo count mismatch for $jamos",
                jamos.length,
                HangulEngine.blocks(jamos).sumOf { it.jamoCount }
            )
        }
    }

    @Test
    fun recognisesJamoAndSyllables() {
        assertTrue(HangulEngine.isJamo('ㄱ'))
        assertTrue(HangulEngine.isJamo('ㅏ'))
        assertTrue(HangulEngine.isJamo('ㅄ'))
        assertFalse(HangulEngine.isJamo('a'))
        assertFalse(HangulEngine.isJamo('한'))   // a composed block is not a jamo
        assertTrue(HangulEngine.isSyllable('한'))
        assertTrue(HangulEngine.isSyllable('가'))   // first block, U+AC00
        assertTrue(HangulEngine.isSyllable('힣'))   // last block, U+D7A3
        assertFalse(HangulEngine.isSyllable('ㄱ'))
        assertFalse(HangulEngine.isSyllable('a'))
    }
}
