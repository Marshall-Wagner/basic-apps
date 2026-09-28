package dev.montb.basiccamera.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Unit tests for the Boost pass's arithmetic. Pure logic, no Android.
 *
 * These guard the two things that are actually audible when this code is wrong: that normalizing
 * never pushes a sample past full scale (clipping is heard as crackle) and that the tail fade
 * really reaches silence (a fade that stops short leaves the stop-click it exists to remove).
 */
class AudioGainTest {

    /** A little-endian PCM buffer from 16-bit samples. */
    private fun pcm(vararg samples: Int): ByteArray {
        val b = ByteArray(samples.size * 2)
        samples.forEachIndexed { i, s -> AudioGain.writeSample(b, i * 2, s) }
        return b
    }

    private fun samplesOf(bytes: ByteArray): List<Int> =
        (0 until bytes.size / 2).map { AudioGain.sampleAt(bytes, it * 2) }

    // --- byte-level encoding ---

    @Test
    fun readsAndWritesLittleEndianSignedSamples() {
        for (s in listOf(0, 1, -1, 1000, -1000, 32767, -32768)) {
            assertEquals(s, AudioGain.sampleAt(pcm(s), 0))
        }
        // Explicit byte order, so a silent endianness flip cannot pass.
        val b = pcm(0x0102)
        assertEquals(0x02.toByte(), b[0])   // low byte first
        assertEquals(0x01.toByte(), b[1])
    }

    @Test
    fun writingClampsInsteadOfWrappingAround() {
        // Wrapping would turn a loud positive sample into a loud negative one, heard as a click.
        assertEquals(32767, AudioGain.sampleAt(pcm(999_999), 0))
        assertEquals(-32768, AudioGain.sampleAt(pcm(-999_999), 0))
    }

    // --- peak measurement ---

    @Test
    fun peakFindsTheLoudestAbsoluteSample() {
        assertEquals(0, AudioGain.peak(pcm(0, 0, 0)))
        assertEquals(5000, AudioGain.peak(pcm(100, -5000, 42)))
        assertEquals(32767, AudioGain.peak(pcm(32767, -1)))
        // abs(-32768) does not overflow here because samples are widened to Int first.
        assertEquals(32768, AudioGain.peak(pcm(-32768)))
    }

    @Test
    fun peakHonoursTheLengthAndIgnoresATrailingOddByte() {
        val b = pcm(100, 30000)
        assertEquals(100, AudioGain.peak(b, length = 2))   // only the first sample
        assertEquals(30000, AudioGain.peak(b))
        // A buffer with a dangling byte cannot form a final sample; it must not be misread.
        assertEquals(100, AudioGain.peak(byteArrayOf(b[0], b[1], 0x7f)))
    }

    // --- gain choice ---

    @Test
    fun gainNormalizesThePeakToTheTargetWithoutClipping() {
        // The defining property: whenever the cap is not in play, peak * gain lands exactly on
        // TARGET_PEAK of full scale, so the result is as loud as possible AND never clips.
        val target = AudioGain.TARGET_PEAK * AudioGain.MAX_SAMPLE
        for (peak in listOf(3000, 5000, 10000, 16384, 32767)) {
            val out = peak * AudioGain.gainFor(peak)
            assertEquals("peak $peak", target, out, 1f)
            assertTrue("peak $peak would clip", out <= AudioGain.MAX_SAMPLE)
        }
    }

    @Test
    fun gainIsCappedSoNearSilenceIsNotAmplifiedIntoNoise() {
        assertEquals(AudioGain.MAX_GAIN, AudioGain.gainFor(1), 0.001f)
        assertEquals(AudioGain.MAX_GAIN, AudioGain.gainFor(100), 0.001f)
        // The cap engages below peak ~2649 (0.97 * 32767 / 12).
        assertEquals(AudioGain.MAX_GAIN, AudioGain.gainFor(2648), 0.001f)
        assertTrue(AudioGain.gainFor(2700) < AudioGain.MAX_GAIN)
    }

    @Test
    fun gainNeverAttenuatesBelowTheTargetOrTouchesSilence() {
        // Silence has no peak to normalize to, so it must pass through untouched rather than
        // being multiplied by a garbage gain.
        assertEquals(1f, AudioGain.gainFor(0), 0f)
        assertEquals(1f, AudioGain.gainFor(-1), 0f)
        // An already-loud clip is only ever nudged, never amplified past full scale.
        assertTrue(AudioGain.gainFor(32767) < 1f)
        assertTrue(AudioGain.gainFor(32768) < 1f)
    }

    // --- fade window ---

    @Test
    fun fadeWindowIsFortyMillisecondsAcrossAllChannels() {
        // Counted in shorts, so stereo needs twice as many for the same duration.
        assertEquals(1920L, AudioGain.fadeShorts(48_000, 1))
        assertEquals(3840L, AudioGain.fadeShorts(48_000, 2))
        assertEquals(3528L, AudioGain.fadeShorts(44_100, 2))
        assertEquals(640L, AudioGain.fadeShorts(16_000, 1))
    }

    @Test
    fun fadeStartIsNeverNegativeForAClipShorterThanTheFade() {
        assertEquals(8000L, AudioGain.fadeStart(9920, 1920))
        // A 10-short clip with a 1920-short fade would otherwise start at -1910 and never fade.
        assertEquals(0L, AudioGain.fadeStart(10, 1920))
    }

    @Test
    fun fadeRampsFromFullToSilence() {
        val start = 1000L
        val len = 100L
        assertEquals(1f, AudioGain.fadeFactor(0, start, len), 0f)
        assertEquals(1f, AudioGain.fadeFactor(999, start, len), 0f)
        assertEquals(1f, AudioGain.fadeFactor(1000, start, len), 0f)
        assertEquals(0.5f, AudioGain.fadeFactor(1050, start, len), 0.001f)
        // Reaching zero is the whole point: a fade that stops short leaves the click.
        assertEquals(0f, AudioGain.fadeFactor(1100, start, len), 0f)
        assertEquals(0f, AudioGain.fadeFactor(5000, start, len), 0f)
    }

    @Test
    fun fadeIsMonotonicAndNeverLeavesTheUnitRange() {
        var prev = 1f
        for (i in 0..200L) {
            val f = AudioGain.fadeFactor(950 + i, 1000L, 100L)
            assertTrue("fade rose at $i", f <= prev + 1e-6f)
            assertTrue("fade out of range at $i", f in 0f..1f)
            prev = f
        }
    }

    @Test
    fun noFadeWindowMeansNoFade() {
        assertEquals(1f, AudioGain.fadeFactor(500, 0L, 0L), 0f)
    }

    // --- the whole transform ---

    @Test
    fun applyScalesSamplesByTheGain() {
        val b = pcm(1000, -2000, 3000)
        AudioGain.apply(b, b.size, gain = 2f, firstIndex = 0, fadeStart = Long.MAX_VALUE, fadeShorts = 0)
        assertEquals(listOf(2000, -4000, 6000), samplesOf(b))
    }

    @Test
    fun applyClampsRatherThanWrappingWhenTheGainOverflows() {
        val b = pcm(20000, -20000)
        AudioGain.apply(b, b.size, gain = 4f, firstIndex = 0, fadeStart = Long.MAX_VALUE, fadeShorts = 0)
        assertEquals(listOf(32767, -32768), samplesOf(b))
    }

    @Test
    fun applyFadesTheTailToSilence() {
        // Four samples, fading across the last two.
        val b = pcm(10000, 10000, 10000, 10000)
        AudioGain.apply(b, b.size, gain = 1f, firstIndex = 0, fadeStart = 2, fadeShorts = 2)
        val out = samplesOf(b)
        assertEquals(10000, out[0])          // before the fade, untouched
        assertEquals(10000, out[1])
        assertEquals(10000, out[2])          // fade begins at full
        assertEquals(5000, out[3])           // halfway down
        // And the sample just past the window is fully silent, which is what kills the click.
        val tail = pcm(10000)
        AudioGain.apply(tail, tail.size, gain = 1f, firstIndex = 4, fadeStart = 2, fadeShorts = 2)
        assertEquals(0, samplesOf(tail).single())
    }

    @Test
    fun applyPlacesTheBufferInTheClipUsingFirstIndex() {
        // The same bytes fade differently depending on where the buffer sits in the recording,
        // which is what keeps the fade continuous across MediaCodec's chunk boundaries.
        val early = pcm(10000, 10000)
        val late = pcm(10000, 10000)
        AudioGain.apply(early, early.size, 1f, firstIndex = 0, fadeStart = 100, fadeShorts = 10)
        AudioGain.apply(late, late.size, 1f, firstIndex = 100, fadeStart = 100, fadeShorts = 10)
        assertEquals(listOf(10000, 10000), samplesOf(early))
        assertEquals(listOf(10000, 9000), samplesOf(late))
    }

    @Test
    fun applyLeavesADanglingOddByteAlone() {
        val b = byteArrayOf(0x10, 0x27, 0x7f)   // one sample (10000) plus a stray byte
        AudioGain.apply(b, b.size, gain = 2f, firstIndex = 0, fadeStart = Long.MAX_VALUE, fadeShorts = 0)
        assertEquals(20000, AudioGain.sampleAt(b, 0))
        assertEquals(0x7f.toByte(), b[2])
    }

    @Test
    fun applyHonoursTheLengthAndIgnoresTheRestOfTheBuffer() {
        // MediaCodec hands back a partly-filled buffer, so anything past n must stay untouched.
        val b = pcm(1000, 1000, 1000)
        AudioGain.apply(b, length = 2, gain = 3f, firstIndex = 0, fadeStart = Long.MAX_VALUE, fadeShorts = 0)
        assertEquals(listOf(3000, 1000, 1000), samplesOf(b))
    }

    @Test
    fun boostingRealisticAudioNeverClips() {
        // End to end over the real pipeline order: measure, choose the gain, apply it. Whatever
        // the input level, the output must stay inside 16-bit range, which is the property that
        // makes the Boost toggle safe to leave on.
        for (level in listOf(50, 500, 2648, 2649, 8000, 20000, 32767)) {
            val b = pcm(level, -level, level / 2, 0, -level)
            val gain = AudioGain.gainFor(AudioGain.peak(b))
            AudioGain.apply(b, b.size, gain, 0, Long.MAX_VALUE, 0)
            val loudest = samplesOf(b).maxOf { abs(it) }
            assertTrue("level $level clipped at $loudest", loudest <= AudioGain.MAX_SAMPLE)
            // Below the cap the result should also actually be loud, not just safe.
            if (level >= 2649) {
                assertTrue(
                    "level $level not normalized, peaked at $loudest",
                    abs(loudest - 31784) <= 2
                )
            }
        }
    }
}
