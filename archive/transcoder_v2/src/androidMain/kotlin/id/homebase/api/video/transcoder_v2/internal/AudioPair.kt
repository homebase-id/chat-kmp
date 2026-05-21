package id.homebase.api.video.transcoder_v2.internal

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import id.homebase.api.video.transcoder_v2.CodecUnavailableException

/**
 * Audio decoder + encoder pair. Always re-encodes to AAC-LC for receiver
 * compatibility (we don't implement Signal's "skip-transcode-if-already-AAC"
 * fast path — SPEC §10 keeps the pipeline simple). Sample format is PCM
 * 16-bit between the codecs.
 */
internal class AudioPair(
    private val extractor: MediaExtractor,
    private val decoder: MediaCodec,
    private val encoder: MediaCodec,
    private val trimStartUs: Long,
    private val trimEndUs: Long,
) : AudioTrack {
    companion object {
        private const val TAG = "AudioPair"
        private const val TIMEOUT_USEC = 10_000L
    }

    override var extractorDone = false; private set
    private var decoderDone = false
    private var encoderDone = false

    /** Implements [AudioTrack.outputDone] — true once encoder emits EOS. */
    override val outputDone: Boolean get() = encoderDone

    override var encoderOutputFormat: MediaFormat? = null; private set
    override var muxingPresentationTimeUs = 0L; private set

    private val decoderInfo = MediaCodec.BufferInfo()
    private val encoderInfo = MediaCodec.BufferInfo()

    private var muxer: MediaMuxer? = null
    private var muxerAudioTrack = -1

    override fun setMuxer(muxer: MediaMuxer, trackIndex: Int) {
        this.muxer = muxer
        this.muxerAudioTrack = trackIndex
    }

    override fun step() {
        feedDecoder()
        drainDecoderToEncoder()
        drainEncoderToMuxer()
    }

    private fun feedDecoder() {
        if (extractorDone) return
        if (encoderOutputFormat != null && muxer == null) return

        val idx = decoder.dequeueInputBuffer(TIMEOUT_USEC)
        if (idx < 0) return
        val buf = decoder.getInputBuffer(idx) ?: return

        val size = extractor.readSampleData(buf, 0)
        val pts = extractor.sampleTime
        val pastEnd = trimEndUs > 0 && pts >= 0 && pts > trimEndUs

        if (size < 0 || pastEnd) {
            extractorDone = true
            decoder.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            return
        }
        decoder.queueInputBuffer(idx, 0, size, pts, extractor.sampleFlags)
        extractor.advance()
    }

    private fun drainDecoderToEncoder() {
        if (decoderDone) return
        if (encoderOutputFormat != null && muxer == null) return

        val idx = decoder.dequeueOutputBuffer(decoderInfo, TIMEOUT_USEC)
        if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) return
        if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) return
        if (idx == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) return
        if (idx < 0) return

        val isEos = (decoderInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
        val isConfig = (decoderInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0

        if (isConfig) {
            decoder.releaseOutputBuffer(idx, false)
            return
        }

        // Drop frames before trim start.
        if (!isEos && decoderInfo.presentationTimeUs < trimStartUs) {
            decoder.releaseOutputBuffer(idx, false)
            return
        }

        val encIdx = encoder.dequeueInputBuffer(TIMEOUT_USEC)
        if (encIdx < 0) {
            // No encoder input slot — try again next step. Don't release decoder
            // output yet (we'd lose audio frames).
            return
        }

        // Normalize PTS so trimmed output starts at t=0; drop-before-trim
        // gate above stays in raw extractor space.
        val normalizedPts = decoderInfo.presentationTimeUs - trimStartUs

        if (decoderInfo.size > 0) {
            val srcBuf = decoder.getOutputBuffer(idx)
            val dstBuf = encoder.getInputBuffer(encIdx)
            if (srcBuf != null && dstBuf != null) {
                srcBuf.position(decoderInfo.offset)
                srcBuf.limit(decoderInfo.offset + decoderInfo.size)
                dstBuf.position(0)
                val toCopy = minOf(decoderInfo.size, dstBuf.capacity())
                val slice = srcBuf.duplicate()
                slice.limit(slice.position() + toCopy)
                dstBuf.put(slice)
                encoder.queueInputBuffer(encIdx, 0, toCopy, normalizedPts, decoderInfo.flags)
            } else {
                encoder.queueInputBuffer(encIdx, 0, 0, normalizedPts, decoderInfo.flags)
            }
        } else {
            encoder.queueInputBuffer(encIdx, 0, 0, normalizedPts, decoderInfo.flags)
        }

        decoder.releaseOutputBuffer(idx, false)
        if (isEos) {
            // Propagate EOS to encoder via a zero-length EOS buffer.
            decoderDone = true
            val eosIdx = encoder.dequeueInputBuffer(TIMEOUT_USEC)
            if (eosIdx >= 0) {
                encoder.queueInputBuffer(eosIdx, 0, 0, normalizedPts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            }
        }
    }

    private fun drainEncoderToMuxer() {
        if (encoderDone) return
        if (encoderOutputFormat != null && muxer == null) return

        val idx = encoder.dequeueOutputBuffer(encoderInfo, TIMEOUT_USEC)
        if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) return
        if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            check(muxerAudioTrack < 0) { "audio encoder changed format twice" }
            encoderOutputFormat = encoder.outputFormat
            return
        }
        if (idx == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) return
        if (idx < 0) return

        val isConfig = (encoderInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
        if (isConfig) {
            encoder.releaseOutputBuffer(idx, false)
            return
        }

        val m = muxer
        check(m != null) { "audio encoder produced sample before muxer attached" }
        val buf = encoder.getOutputBuffer(idx)
        if (buf != null && encoderInfo.size > 0) {
            m.writeSampleData(muxerAudioTrack, buf, encoderInfo)
            muxingPresentationTimeUs = maxOf(muxingPresentationTimeUs, encoderInfo.presentationTimeUs)
        }
        encoder.releaseOutputBuffer(idx, false)
        if ((encoderInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
            encoderDone = true
        }
    }

    override fun release(): Throwable? {
        var firstErr: Throwable? = null
        runCatching { decoder.stop() }.exceptionOrNull()?.let { firstErr = it; Log.w(TAG, "decoder.stop", it) }
        runCatching { decoder.release() }.exceptionOrNull()?.let { if (firstErr == null) firstErr = it; Log.w(TAG, "decoder.release", it) }
        runCatching { encoder.stop() }.exceptionOrNull()?.let { if (firstErr == null) firstErr = it; Log.w(TAG, "encoder.stop", it) }
        runCatching { encoder.release() }.exceptionOrNull()?.let { if (firstErr == null) firstErr = it; Log.w(TAG, "encoder.release", it) }
        return firstErr
    }
}

internal object AudioTrackFactory {
    private const val TAG = "AudioTrack"
    private const val SAMPLE_BUFFER_SIZE = 16 * 1024

    /**
     * For tests: counts the number of audio codecs constructed in the
     * most recent factory call. Pass-through path increments 0; re-encode
     * path increments 2 (decoder + encoder).
     */
    @Volatile var lastCodecConstructionCount: Int = 0
        private set

    /** Returns null if the input has no audio track. */
    fun create(
        inputPath: String,
        profile: QualityProfile,
        trimStartUs: Long,
        trimEndUs: Long,
    ): AudioTrack? {
        val extractor = MediaExtractor()
        extractor.setDataSource(inputPath)
        val (trackIndex, inputFormat) = selectAudioTrack(extractor) ?: run {
            extractor.release()
            return null
        }
        extractor.selectTrack(trackIndex)

        // Pass-through fast path: AAC input at acceptable bitrate. Mirrors
        // Signal's `AudioTrackConverter.formatCanSkipTranscode`. Missing
        // KEY_BIT_RATE → can't pass through (fall through to re-encode).
        if (canSkipTranscode(inputFormat, profile.audioBitrateBps)) {
            if (trimStartUs > 0) {
                extractor.seekTo(trimStartUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            }
            lastCodecConstructionCount = 0
            Log.d(TAG, "audio pass-through (input already AAC at acceptable bitrate)")
            return AudioRemuxer(
                extractor = extractor,
                inputFormat = inputFormat,
                trimStartUs = trimStartUs,
                trimEndUs = trimEndUs,
            )
        }

        // Re-encode path: decoder + encoder.
        lastCodecConstructionCount = 0

        val sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channelCount = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

        val outFormat = MediaFormat.createAudioFormat(profile.audioCodec, sampleRate, channelCount).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, profile.audioBitrateBps)
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, SAMPLE_BUFFER_SIZE)
        }

        val encoderCandidates = CodecSelection.selectEncoders(profile.audioCodec)
        if (encoderCandidates.isEmpty()) {
            extractor.release()
            throw CodecUnavailableException(codecMimeType = profile.audioCodec, isEncoder = true)
        }
        var encoder: MediaCodec? = null
        var lastErr: Throwable? = null
        for ((i, info) in encoderCandidates.withIndex()) {
            try {
                encoder = MediaCodec.createByCodecName(info.name)
                lastCodecConstructionCount++
                encoder.configure(outFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                encoder.start()
                if (i > 0) Log.w(TAG, "audio encoder OK fallback ${info.name}")
                break
            } catch (e: Throwable) {
                Log.w(TAG, "audio encoder ${info.name} failed", e)
                lastErr = e
                try { encoder?.release() } catch (_: Exception) {}
                encoder = null
            }
        }
        if (encoder == null) {
            extractor.release()
            throw CodecUnavailableException(codecMimeType = profile.audioCodec, isEncoder = true, cause = lastErr)
        }

        val inputMime = inputFormat.getString(MediaFormat.KEY_MIME)!!
        val decoder = MediaCodec.createDecoderByType(inputMime)
        lastCodecConstructionCount++
        try {
            decoder.configure(inputFormat, null, null, 0)
            decoder.start()
        } catch (e: Throwable) {
            decoder.release()
            encoder.stop(); encoder.release()
            extractor.release()
            throw CodecUnavailableException(codecMimeType = inputMime, isEncoder = false, cause = e)
        }

        if (trimStartUs > 0) {
            extractor.seekTo(trimStartUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        }

        return AudioPair(
            extractor = extractor,
            decoder = decoder,
            encoder = encoder,
            trimStartUs = trimStartUs,
            trimEndUs = trimEndUs,
        )
    }

    /** Signal's AudioTrackConverter.formatCanSkipTranscode equivalent. */
    private fun canSkipTranscode(inputFormat: MediaFormat, targetBitrateBps: Int): Boolean {
        val mime = try { inputFormat.getString(MediaFormat.KEY_MIME) } catch (_: Exception) { null }
        if (mime != MediaFormat.MIMETYPE_AUDIO_AAC) return false
        val inputBitrate = try {
            inputFormat.getInteger(MediaFormat.KEY_BIT_RATE)
        } catch (_: NullPointerException) {
            return false  // missing key — treat as "cannot pass through" (Signal does same)
        } catch (_: Exception) {
            return false
        }
        return inputBitrate <= targetBitrateBps
    }

    private fun selectAudioTrack(extractor: MediaExtractor): Pair<Int, MediaFormat>? {
        for (i in 0 until extractor.trackCount) {
            val fmt = extractor.getTrackFormat(i)
            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) return i to fmt
        }
        return null
    }
}
