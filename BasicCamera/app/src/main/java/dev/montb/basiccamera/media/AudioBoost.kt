package dev.montb.basiccamera.media

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.abs
import kotlin.math.min

/**
 * Optional, opt-in post-recording audio pass (the "Boost" toggle). It PEAK-normalizes the audio,
 * one constant gain so the loudest sample lands near full scale, which lifts a quiet recording
 * WITHOUT compressing dynamics (quiet and loud stay proportional, so music keeps its contrast and
 * nothing clips), then fades the final few milliseconds to remove the stop-click. The video track
 * is copied through untouched and the two are re-muxed.
 *
 * Every step is guarded: on ANY failure the original MediaStore recording is left exactly as it
 * was. This can never corrupt a video; worst case the boost simply doesn't apply.
 *
 * Runs off the main thread. Uses temp files in the cache dir, decoding once to measure the peak,
 * then again to apply the gain, so memory stays bounded regardless of clip length.
 */
object AudioBoost {

    private const val TARGET_PEAK = 0.97f        // normalize the loudest sample to ~ -0.26 dBFS
    private const val MAX_GAIN = 12f             // don't over-amplify a near-silent clip (just noise)
    private const val FADE_MS = 40               // fade the tail to kill the stop-click
    private const val TIMEOUT_US = 10_000L

    fun process(context: Context, videoUri: Uri) {
        val cache = context.cacheDir
        val input = File.createTempFile("boost_in_", ".mp4", cache)
        val pcm = File.createTempFile("boost_pcm_", ".raw", cache)
        val output = File.createTempFile("boost_out_", ".mp4", cache)
        try {
            context.contentResolver.openInputStream(videoUri)?.use { ins ->
                input.outputStream().use { ins.copyTo(it) }
            } ?: return
            if (!transcode(input, pcm, output)) return
            // Overwrite the MediaStore entry in place with the processed file.
            context.contentResolver.openOutputStream(videoUri, "wt")?.use { outs ->
                output.inputStream().use { it.copyTo(outs) }
            }
        } catch (_: Throwable) {
            // Leave the original recording untouched.
        } finally {
            input.delete(); pcm.delete(); output.delete()
        }
    }

    // ---- Always-on, lossless de-click (used when Boost is off) ----

    private const val DECLICK_TRIM_US = 150_000L

    /**
     * Lossless de-click: re-mux the recording, dropping the final ~150 ms of audio, where the
     * stop-click sits, most often the mechanical noise of the volume-rocker press the mic picks
     * up when you stop with the side button. The video and all earlier audio are copied
     * bit-for-bit (no re-encode), so quality is untouched; only the tail after you press stop
     * (non-content) is trimmed. Guarded so a failure leaves the original recording exactly as it was.
     */
    fun declick(context: Context, videoUri: Uri) {
        val cache = context.cacheDir
        val input = File.createTempFile("declick_in_", ".mp4", cache)
        val output = File.createTempFile("declick_out_", ".mp4", cache)
        try {
            context.contentResolver.openInputStream(videoUri)?.use { ins ->
                input.outputStream().use { ins.copyTo(it) }
            } ?: return
            if (!remuxTrimmingTail(input, output)) return
            context.contentResolver.openOutputStream(videoUri, "wt")?.use { outs ->
                output.inputStream().use { it.copyTo(outs) }
            }
        } catch (_: Throwable) {
            // Original recording left untouched.
        } finally {
            input.delete(); output.delete()
        }
    }

    private fun remuxTrimmingTail(input: File, output: File): Boolean {
        val ex = MediaExtractor()
        ex.setDataSource(input.absolutePath)
        var aIdx = -1; var vIdx = -1
        var aFmt: MediaFormat? = null; var vFmt: MediaFormat? = null
        for (i in 0 until ex.trackCount) {
            val f = ex.getTrackFormat(i)
            val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
            when {
                mime.startsWith("audio/") && aIdx < 0 -> { aIdx = i; aFmt = f }
                mime.startsWith("video/") && vIdx < 0 -> { vIdx = i; vFmt = f }
            }
        }
        if (aIdx < 0 || vIdx < 0 || aFmt == null || vFmt == null) { ex.release(); return false }

        // Find the last audio timestamp so we can trim a fixed window off the end.
        ex.selectTrack(aIdx)
        var lastPts = -1L
        while (true) {
            val t = ex.sampleTime
            if (t < 0) break
            if (t > lastPts) lastPts = t
            ex.advance()
        }
        ex.unselectTrack(aIdx)
        if (lastPts < 0) { ex.release(); return false }
        val cutoff = lastPts - DECLICK_TRIM_US

        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val muxV = muxer.addTrack(vFmt)
        val muxA = muxer.addTrack(aFmt)
        muxer.start()

        val buf = java.nio.ByteBuffer.allocate(maxOf(bufCap(vFmt), bufCap(aFmt)))
        val info = MediaCodec.BufferInfo()
        copyTrack(ex, vIdx, muxV, muxer, buf, info, Long.MAX_VALUE)   // all video
        copyTrack(ex, aIdx, muxA, muxer, buf, info, cutoff)           // audio minus the click tail

        muxer.stop(); muxer.release()
        ex.release()
        return true
    }

    private fun copyTrack(
        ex: MediaExtractor, trackIdx: Int, muxTrack: Int, muxer: MediaMuxer,
        buf: java.nio.ByteBuffer, info: MediaCodec.BufferInfo, maxPtsInclusive: Long
    ) {
        ex.selectTrack(trackIdx)
        ex.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
        while (true) {
            val sz = ex.readSampleData(buf, 0)
            if (sz < 0) break
            val pts = ex.sampleTime
            if (pts > maxPtsInclusive) break
            info.offset = 0
            info.size = sz
            info.presentationTimeUs = pts
            info.flags = if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0)
                MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
            muxer.writeSampleData(muxTrack, buf, info)
            ex.advance()
        }
        ex.unselectTrack(trackIdx)
    }

    private fun bufCap(fmt: MediaFormat): Int =
        if (fmt.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE))
            fmt.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE).coerceAtLeast(1 shl 20) else 1 shl 22

    private fun transcode(input: File, pcm: File, output: File): Boolean {
        val ex = MediaExtractor()
        ex.setDataSource(input.absolutePath)
        var aIdx = -1; var vIdx = -1
        var aFmt: MediaFormat? = null; var vFmt: MediaFormat? = null
        for (i in 0 until ex.trackCount) {
            val f = ex.getTrackFormat(i)
            val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
            when {
                mime.startsWith("audio/") && aIdx < 0 -> { aIdx = i; aFmt = f }
                mime.startsWith("video/") && vIdx < 0 -> { vIdx = i; vFmt = f }
            }
        }
        if (aIdx < 0 || vIdx < 0 || aFmt == null || vFmt == null) { ex.release(); return false }
        val sampleRate = aFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channels = aFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

        // ---- Pass 1: decode audio to a raw little-endian PCM file; find the peak sample. ----
        var peak = 0
        var totalShorts = 0L
        ex.selectTrack(aIdx)
        val dec = MediaCodec.createDecoderByType(aFmt.getString(MediaFormat.KEY_MIME)!!)
        dec.configure(aFmt, null, null, 0)
        dec.start()
        val dInfo = MediaCodec.BufferInfo()
        var dInEos = false; var dOutEos = false
        pcm.outputStream().buffered().use { pcmOut ->
            while (!dOutEos) {
                if (!dInEos) {
                    val inIdx = dec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIdx >= 0) {
                        val buf = dec.getInputBuffer(inIdx)!!
                        val sz = ex.readSampleData(buf, 0)
                        if (sz < 0) {
                            dec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            dInEos = true
                        } else {
                            dec.queueInputBuffer(inIdx, 0, sz, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val outIdx = dec.dequeueOutputBuffer(dInfo, TIMEOUT_US)
                if (outIdx >= 0) {
                    if (dInfo.size > 0) {
                        val ob = dec.getOutputBuffer(outIdx)!!
                        ob.position(dInfo.offset); ob.limit(dInfo.offset + dInfo.size)
                        val bytes = ByteArray(dInfo.size)
                        ob.get(bytes)
                        var i = 0
                        while (i + 1 < bytes.size) {
                            val s = ((bytes[i].toInt() and 0xff) or (bytes[i + 1].toInt() shl 8)).toShort().toInt()
                            val a = abs(s); if (a > peak) peak = a
                            i += 2
                        }
                        pcmOut.write(bytes)
                        totalShorts += bytes.size / 2
                    }
                    dec.releaseOutputBuffer(outIdx, false)
                    if (dInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) dOutEos = true
                }
            }
        }
        dec.stop(); dec.release()
        ex.unselectTrack(aIdx)
        if (peak == 0 || totalShorts == 0L) { ex.release(); return false }

        val gain = min(MAX_GAIN, (TARGET_PEAK * 32767f) / peak)
        val fadeShorts = FADE_MS.toLong() * sampleRate / 1000L * channels
        val fadeStart = (totalShorts - fadeShorts).coerceAtLeast(0)

        // ---- Pass 2: read the PCM back with gain + tail fade, encode to AAC, mux with the video. ----
        val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
            enc.configure(this, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
        enc.start()
        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxA = -1; var muxV = -1; var started = false
        val eInfo = MediaCodec.BufferInfo()

        val pcmIn = RandomAccessFile(pcm, "r")
        val chunk = ByteArray(2048 * channels)
        var shortsRead = 0L
        var frames = 0L
        var eInEos = false; var eOutEos = false
        while (!eOutEos) {
            if (!eInEos) {
                val inIdx = enc.dequeueInputBuffer(TIMEOUT_US)
                if (inIdx >= 0) {
                    val ib = enc.getInputBuffer(inIdx)!!
                    ib.clear()
                    val want = min(ib.remaining(), chunk.size)
                    val n = pcmIn.read(chunk, 0, want)
                    if (n <= 0) {
                        enc.queueInputBuffer(inIdx, 0, 0, frames * 1_000_000L / sampleRate, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        eInEos = true
                    } else {
                        var i = 0
                        while (i + 1 < n) {
                            val s = ((chunk[i].toInt() and 0xff) or (chunk[i + 1].toInt() shl 8)).toShort().toInt()
                            var g = gain
                            val idx = shortsRead + i / 2
                            if (fadeShorts > 0 && idx >= fadeStart) {
                                g *= (1f - (idx - fadeStart).toFloat() / fadeShorts).coerceIn(0f, 1f)
                            }
                            var v = (s * g).toInt()
                            if (v > 32767) v = 32767 else if (v < -32768) v = -32768
                            chunk[i] = (v and 0xff).toByte()
                            chunk[i + 1] = ((v shr 8) and 0xff).toByte()
                            i += 2
                        }
                        ib.put(chunk, 0, n)
                        enc.queueInputBuffer(inIdx, 0, n, frames * 1_000_000L / sampleRate, 0)
                        shortsRead += n / 2
                        frames += (n / 2) / channels
                    }
                }
            }
            val outIdx = enc.dequeueOutputBuffer(eInfo, TIMEOUT_US)
            if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                muxA = muxer.addTrack(enc.outputFormat)
                muxV = muxer.addTrack(vFmt)
                muxer.start(); started = true
            } else if (outIdx >= 0) {
                if (eInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) eInfo.size = 0
                if (eInfo.size > 0 && started) {
                    val ob = enc.getOutputBuffer(outIdx)!!
                    ob.position(eInfo.offset); ob.limit(eInfo.offset + eInfo.size)
                    muxer.writeSampleData(muxA, ob, eInfo)
                }
                enc.releaseOutputBuffer(outIdx, false)
                if (eInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) eOutEos = true
            }
        }
        pcmIn.close()
        enc.stop(); enc.release()
        if (!started) { muxer.release(); ex.release(); return false }

        // Copy the compressed video samples straight through.
        ex.selectTrack(vIdx)
        ex.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
        val cap = if (vFmt.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE))
            vFmt.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE).coerceAtLeast(1 shl 20) else 1 shl 20
        val vBuf = java.nio.ByteBuffer.allocate(cap)
        val vInfo = MediaCodec.BufferInfo()
        while (true) {
            val sz = ex.readSampleData(vBuf, 0)
            if (sz < 0) break
            vInfo.offset = 0
            vInfo.size = sz
            vInfo.presentationTimeUs = ex.sampleTime
            vInfo.flags = if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0)
                MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
            muxer.writeSampleData(muxV, vBuf, vInfo)
            ex.advance()
        }
        muxer.stop(); muxer.release()
        ex.release()
        return true
    }
}
