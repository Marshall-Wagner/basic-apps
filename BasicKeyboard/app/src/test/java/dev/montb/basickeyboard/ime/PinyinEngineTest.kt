package dev.montb.basickeyboard.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pinyin lookup: pure data in, candidates out, no Android. Uses a tiny
 * in-memory dictionary so the ranking rules are checked exactly rather than against the
 * shipped seed file.
 */
class PinyinEngineTest {

    private val sample = """
        # a comment, ignored

        ni 你 900
        ni 泥 100
        hao 好 800
        nihao 你好 1000
        nimen 你们 500
        ma 吗 640
    """.trimIndent()

    private val dict = PinyinEngine.parse(sample.lineSequence())
    private val keys = dict.keys.sorted()

    private fun words(input: String, limit: Int = 10): List<String> =
        PinyinEngine.lookup(dict, keys, input, limit).map { it.word }

    @Test
    fun parseSkipsCommentsAndBlankLines() {
        assertEquals(setOf("ni", "hao", "nihao", "nimen", "ma"), dict.keys)
    }

    @Test
    fun entriesSharingAPinyinAreSortedByFrequency() {
        assertEquals(listOf("你", "泥"), dict["ni"]!!.map { it.word })
    }

    @Test
    fun exactMatchOnEverythingTypedComesFirst() {
        assertEquals("你好", words("nihao").first())
    }

    @Test
    fun predictionsFollowExactMatchesRankedByFrequency() {
        // "ni": its own entries first (你, 泥), then longer keys starting with "ni" ordered by
        // frequency, so 你好 (1000) beats 你们 (500).
        assertEquals(listOf("你", "泥", "你好", "你们"), words("ni"))
    }

    @Test
    fun candidateReportsHowMuchPinyinItConsumed() {
        // Needed so picking a candidate leaves the leftover pinyin composing.
        val phrase = PinyinEngine.lookup(dict, keys, "nihao", 10).first { it.word == "你好" }
        assertEquals(5, phrase.keyLen)
        val single = PinyinEngine.lookup(dict, keys, "ni", 10).first { it.word == "你" }
        assertEquals(2, single.keyLen)
    }

    @Test
    fun fallsBackToTheLongestKnownPrefix() {
        // Nothing matches "nihaoxyz" whole, so the longest prefix that IS a key wins.
        assertEquals("你好", words("nihaoxyz").first())
    }

    @Test
    fun emptyInputHasNoCandidates() {
        assertTrue(words("").isEmpty())
    }

    @Test
    fun limitIsRespected() {
        assertEquals(1, words("ni", limit = 1).size)
    }

    @Test
    fun prefixKeysFindsOnlyMatchingKeys() {
        assertEquals(listOf("ni", "nihao", "nimen"), PinyinEngine.prefixKeys(keys, "ni"))
        assertTrue(PinyinEngine.prefixKeys(keys, "zzz").isEmpty())
    }
}
