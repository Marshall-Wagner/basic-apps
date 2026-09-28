package dev.montb.basiccamera.media

import kotlin.math.abs
import kotlin.math.min

/**
 * The arithmetic behind the Boost pass: peak measurement, the gain choice, and the per-sample gain
 * plus tail fade.
 *
 * Split out of [AudioBoost] because that object is welded to MediaExtractor/MediaCodec/MediaMuxer
 * and so can only run on a device, while everything that is actually audible, how loud the result
 * is, whether it clips, and whether the stop-click is gone, is decided by the plain arithmetic
 * here. Keeping it separate is what makes that half testable off-device.
 *
 * Audio is 16-bit signed little-endian PCM throughout, which is what the AAC decoder emits.
 */
internal object AudioGain {

    /** Normalize the loudest sample to ~ -0.26 dBFS, leaving a sliver of headroom. */
    const val TARGET_PEAK = 0.97f

    /** Ceiling on the gain, so a near-silent clip is not amplified into pure noise. */
    const val MAX_GAIN = 12f

    /** Length of the tail fade that removes the stop-click. */
    const val FADE_MS = 40

    const val MIN_SAMPLE = -32768
    const val MAX_SAMPLE = 32767

    /** The little-endian 16-bit sample starting at byte [i], sign-extended. */
    fun sampleAt(bytes: ByteArray, i: Int): Int =
        ((bytes[i].toInt() and 0xff) or (bytes[i + 1].toInt() shl 8)).toShort().toInt()

    /** Write [value] at byte [i], clamped to the 16-bit range so a boosted sample cannot wrap
     *  around from loud-positive to loud-negative (which is heard as a hard click). */
    fun writeSample(bytes: ByteArray, i: Int, value: Int) {
        val v = value.coerceIn(MIN_SAMPLE, MAX_SAMPLE)
        bytes[i] = (v and 0xff).toByte()
        bytes[i + 1] = ((v shr 8) and 0xff).toByte()
    }

    /** Largest absolute sample in the first [length] bytes, or 0 for silence. A trailing odd byte
     *  is ignored, since it cannot form a sample. */
    fun peak(bytes: ByteArray, length: Int = bytes.size): Int {
        var peak = 0
        var i = 0
        while (i + 1 < length) {
            val a = abs(sampleAt(bytes, i))
            if (a > peak) peak = a
            i += 2
        }
        return peak
    }

    /**
     * The single constant gain applied to the whole clip: enough to lift [peak] to [TARGET_PEAK]
     * of full scale, capped at [MAX_GAIN]. One gain for the entire recording is what makes this
     * normalization rather than compression, so quiet and loud passages stay proportional and
     * music keeps its contrast.
     */
    fun gainFor(peak: Int): Float =
        if (peak <= 0) 1f else min(MAX_GAIN, (TARGET_PEAK * MAX_SAMPLE) / peak)

    /** Length of the tail fade in shorts (samples across all channels), not frames. */
    fun fadeShorts(sampleRate: Int, channels: Int): Long =
        FADE_MS.toLong() * sampleRate / 1000L * channels

    /** Where the fade begins, given a clip of [totalShorts]. Never negative, so a clip shorter
     *  than the fade window simply fades from its first sample. */
    fun fadeStart(totalShorts: Long, fadeShorts: Long): Long =
        (totalShorts - fadeShorts).coerceAtLeast(0)

    /** Fade multiplier at [index]: 1 before the fade, ramping linearly to 0 at the end. */
    fun fadeFactor(index: Long, fadeStart: Long, fadeShorts: Long): Float =
        if (fadeShorts <= 0 || index < fadeStart) 1f
        else (1f - (index - fadeStart).toFloat() / fadeShorts).coerceIn(0f, 1f)

    /**
     * Apply [gain] and the tail fade to the first [length] bytes of [bytes], in place.
     * [firstIndex] is the position (in shorts) of this buffer's first sample within the whole
     * clip, which is what places the buffer relative to the fade.
     */
    fun apply(
        bytes: ByteArray,
        length: Int,
        gain: Float,
        firstIndex: Long,
        fadeStart: Long,
        fadeShorts: Long
    ) {
        var i = 0
        while (i + 1 < length) {
            val g = gain * fadeFactor(firstIndex + i / 2, fadeStart, fadeShorts)
            writeSample(bytes, i, (sampleAt(bytes, i) * g).toInt())
            i += 2
        }
    }
}
