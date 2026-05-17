package id.homebase.api.video.transcoder_v2.internal

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.nio.ByteBuffer

/**
 * Pass-through audio track: copies samples from the extractor directly to
 * the muxer with no codec round-trip. Used when input audio is already
 * AAC at acceptable bitrate. Mirrors Signal's `AudioTrackConverter.extractAndRemux`.
 *
 * Two correctness wins over the AudioPair re-encode path:
 *  1. Saves CPU on the common case (most phone videos are AAC-LC).
 *  2. Sidesteps the documented HE-AAC re-encode bug — re-encoding HE-AAC
 *     decodes to PCM via an AAC-LC decoder that produces mangled output
 *     PTS, which the encoder then re-quantizes incorrectly. Pass-through
 *     preserves the original HE-AAC bitstream unchanged.
 */
internal class AudioRemuxer(
    private val extractor: MediaExtractor,
    private val inputFormat: MediaFormat,
    private val trimStartUs: Long,
    private val trimEndUs: Long,  // 0 = no end trim
) : AudioTrack {

    companion object {
        private const val TAG = "AudioRemuxer"
        private const val SAMPLE_BUFFER_BYTES = 16 * 1024
    }

    override var extractorDone = false; private set
    override var outputDone = false; private set

    // Available immediately — no encoder to wait for. `TranscodePump.maybeStartMuxer`
    // sees this and starts the muxer as soon as the video side's format arrives.
    override val encoderOutputFormat: MediaFormat? = inputFormat

    override var muxingPresentationTimeUs = 0L; private set

    private val sampleBuf: ByteBuffer = ByteBuffer.allocateDirect(SAMPLE_BUFFER_BYTES)
    private val bufferInfo = MediaCodec.BufferInfo()

    private var muxer: MediaMuxer? = null
    private var muxerAudioTrack = -1

    override fun setMuxer(muxer: MediaMuxer, trackIndex: Int) {
        this.muxer = muxer
        this.muxerAudioTrack = trackIndex
    }

    override fun step() {
        if (outputDone) return
        val m = muxer ?: return  // wait for muxer attach (TranscodePump.maybeStartMuxer)

        sampleBuf.clear()
        val size = extractor.readSampleData(sampleBuf, 0)
        val sampleTime = extractor.sampleTime
        val pastEnd = trimEndUs > 0 && sampleTime >= 0 && sampleTime > trimEndUs

        if (size < 0 || pastEnd) {
            // EOS. Flip flags BEFORE returning so the next pump iteration's
            // isComplete() sees us done.
            extractorDone = true
            outputDone = true
            return
        }

        // Drop samples whose raw PTS lands before the trim start. AAC frames
        // are typically all "sync" so SEEK_TO_PREVIOUS_SYNC usually lands at
        // or after trimStartUs, but containers with audio sync intervals can
        // undershoot. Mirrors VideoPair's drop-before-trim guard.
        if (sampleTime < trimStartUs) {
            extractor.advance()
            return
        }

        // Normalize PTS to 0-based (subtract trimStartUs).
        val normalizedPts = sampleTime - trimStartUs
        bufferInfo.set(0, size, normalizedPts, extractor.sampleFlags)

        m.writeSampleData(muxerAudioTrack, sampleBuf, bufferInfo)
        muxingPresentationTimeUs = maxOf(muxingPresentationTimeUs, normalizedPts)
        extractor.advance()
    }

    override fun release(): Throwable? {
        return runCatching { extractor.release() }.exceptionOrNull()
            ?.also { Log.w(TAG, "extractor.release", it) }
    }
}
