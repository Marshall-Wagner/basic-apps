package dev.montb.basicmonitor.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for SystemStats' pure helpers: clock-unit normalization and SoC-code matching.
 * No Android (no Build, no Context, no sysfs reads).
 */
class SystemStatsTest {

    // --- toMhz: normalize a raw clock value (Hz / kHz / MHz) to MHz by magnitude ---

    @Test
    fun hertzValueIsConvertedToMhz() {
        assertEquals(700, SystemStats.toMhz(700_000_000L)) // 700 MHz expressed in Hz
    }

    @Test
    fun kilohertzValueIsConvertedToMhz() {
        assertEquals(700, SystemStats.toMhz(700_000L)) // 700 MHz expressed in kHz
    }

    @Test
    fun megahertzValueIsLeftAsIs() {
        assertEquals(700, SystemStats.toMhz(700L)) // already MHz
    }

    @Test
    fun toMhzThresholdsMatchTheHeuristic() {
        // Documents the magnitude cut-offs: > 1e6 = Hz, > 1e4 = kHz, else already MHz.
        assertEquals(1000, SystemStats.toMhz(1_000_000L)) // not > 1e6, so read as kHz -> 1000
        assertEquals(10_000, SystemStats.toMhz(10_000L))  // not > 1e4, so read as MHz as-is
    }

    // --- matchSoc: map an SoC code found anywhere in a string to its marketing name ---

    @Test
    fun matchesAKnownSocCode() {
        assertEquals("Snapdragon 8+ Gen 1 (SM8475)", SystemStats.matchSoc("SM8475"))
    }

    @Test
    fun matchesSocCodeEmbeddedAndCaseInsensitive() {
        assertEquals("Snapdragon 888 (SM8350)", SystemStats.matchSoc("Qualcomm sm8350 board"))
    }

    @Test
    fun unknownSocReturnsNull() {
        assertNull(SystemStats.matchSoc("MediaTek Dimensity 9000"))
    }
}
