package id.homebase.api.video.transcoder_v2.internal

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import id.homebase.api.video.transcoder_v2.CodecUnavailableException
import id.homebase.api.video.transcoder_v2.GlPipelineException
import id.homebase.api.video.transcoder_v2.internal.gl.InputSurface
import id.homebase.api.video.transcoder_v2.internal.gl.OutputSurface

/**
 * Holds the video decoder + video encoder pair plus the per-track
 * extractor state and the GL surface bridge between them. `step()` is the
 * one-tick state machine; the bridge flows decoder frames directly into
 * the encoder via a `SurfaceTexture` → textured-quad → encoder-input-surface
 * pipeline. See SPEC.md §10 amendment.
 *
 * Pipeline: extractor → decoder (Surface out) → SurfaceTexture →
 * GL textured quad → encoder (Surface in) → muxer. Scaling between source
 * dims and encoder dims is implicit (GL bilinear via `GL_LINEAR`).
 */
internal class VideoPair(
    private val extractor: MediaExtractor,
    private val videoTrackIndex: Int,
    private val decoder: MediaCodec,
    private val encoder: MediaCodec,
    private val inputSurface: InputSurface,
    private val outputSurface: OutputSurface,
    private val trimStartUs: Long,
    private val trimEndUs: Long,  // 0 = no end trim
    val decoderName: String,
) {
    companion object {
        private const val TAG = "VideoPair"
        private const val TIMEOUT_USEC = 10_000L
    }

    var extractorDone = false; private set
    var decoderDone = false; private set
    var encoderDone = false; private set

    /** Set once the encoder emits INFO_OUTPUT_FORMAT_CHANGED. */
    var encoderOutputFormat: MediaFormat? = null; private set

    /** Latest muxed PTS, used by progress + the interleave heuristic. */
    var muxingPresentationTimeUs = 0L; private set

    /** Frame counters surfaced for stuck-frame watchdog. */
    var extractedFrames = 0; private set
    var decodedFrames = 0; private set
    var encodedFrames = 0; private set

    private val decoderInfo = MediaCodec.BufferInfo()
    private val encoderInfo = MediaCodec.BufferInfo()

    private var muxer: MediaMuxer? = null
    private var muxerVideoTrack = -1

    fun setMuxer(muxer: MediaMuxer, trackIndex: Int) {
        this.muxer = muxer
        this.muxerVideoTrack = trackIndex
    }

    fun step() {
        feedDecoder()
        drainDecoderToEncoder()
        drainEncoderToMuxer()
    }

    private fun feedDecoder() {
        if (extractorDone) return
        // Don't feed past the format-changed barrier; wait for muxer attach.
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
        extractedFrames++
    }

    private fun drainDecoderToEncoder() {
        if (decoderDone) return
        if (encoderOutputFormat != null && muxer == null) return  // wait for muxer

        val idx = decoder.dequeueOutputBuffer(decoderInfo, TIMEOUT_USEC)
        if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) return
        if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            Log.d(TAG, "video decoder: output format ${decoder.outputFormat}")
            return
        }
        if (idx == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) return  // deprecated, ignore
        if (idx < 0) return

        val isEos = (decoderInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
        val isConfig = (decoderInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0

        if (isConfig) {
            decoder.releaseOutputBuffer(idx, false)
            return
        }

        // Drop frames before trim start (extractor seeked to nearest sync sample).
        if (!isEos && decoderInfo.presentationTimeUs < trimStartUs) {
            decoder.releaseOutputBuffer(idx, false)
            return
        }

        val render = decoderInfo.size > 0
        // releaseOutputBuffer(idx, true) is the surface-mode "commit this frame
        // to my output Surface" call. The decoder writes pixels into our
        // OutputSurface's SurfaceTexture; onFrameAvailable fires async.
        decoder.releaseOutputBuffer(idx, render)

        if (render) {
            outputSurface.awaitNewImage()                                        // wait for the frame to arrive on the texture
            outputSurface.drawImage()                                            // shader paints texture → encoder's input surface
            // Normalize PTS so trimmed output starts at t=0 in container time
            // (subtract trimStartUs; raw extractor PTS would leave a leading
            // gap equal to trimStartUs that some players don't handle).
            val normalizedUs = decoderInfo.presentationTimeUs - trimStartUs
            inputSurface.setPresentationTime(normalizedUs * 1000L)               // µs → ns
            inputSurface.swapBuffers()                                           // commit this frame to the encoder
            decodedFrames++
        }

        if (isEos) {
            decoderDone = true
            signalEncoderEos()
        }
    }

    private fun signalEncoderEos() {
        // Surface-input encoders use signalEndOfInputStream — they don't have
        // input buffer queues to enqueue a zero-length EOS buffer into.
        try {
            encoder.signalEndOfInputStream()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "signalEndOfInputStream", e)
        }
    }

    private fun drainEncoderToMuxer() {
        if (encoderDone) return
        if (encoderOutputFormat != null && muxer == null) return

        val idx = encoder.dequeueOutputBuffer(encoderInfo, TIMEOUT_USEC)
        if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) return
        if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            check(muxerVideoTrack < 0) { "video encoder changed format twice" }
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
        check(m != null) { "encoder produced sample before muxer attached" }
        val buf = encoder.getOutputBuffer(idx)
        if (buf != null && encoderInfo.size > 0) {
            m.writeSampleData(muxerVideoTrack, buf, encoderInfo)
            muxingPresentationTimeUs = maxOf(muxingPresentationTimeUs, encoderInfo.presentationTimeUs)
            encodedFrames++
        }
        encoder.releaseOutputBuffer(idx, false)
        if ((encoderInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
            encoderDone = true
        }
    }

    fun release(): Throwable? {
        var firstErr: Throwable? = null
        // SPEC §5 / §10-amendment teardown order:
        //   1. stop codecs (encoder writes trailing samples; decoder stops accepting input)
        //   2. release OutputSurface (frees SurfaceTexture binding)
        //   3. release InputSurface (destroys EGL context + window surface wrapping encoder's input)
        //   4. release codecs (after their surfaces are torn down)
        runCatching { decoder.stop() }.exceptionOrNull()?.let { firstErr = it; Log.w(TAG, "decoder.stop", it) }
        runCatching { encoder.stop() }.exceptionOrNull()?.let { if (firstErr == null) firstErr = it; Log.w(TAG, "encoder.stop", it) }
        runCatching { outputSurface.release() }.exceptionOrNull()?.let { if (firstErr == null) firstErr = it; Log.w(TAG, "outputSurface.release", it) }
        runCatching { decoder.release() }.exceptionOrNull()?.let { if (firstErr == null) firstErr = it; Log.w(TAG, "decoder.release", it) }
        runCatching { encoder.release() }.exceptionOrNull()?.let { if (firstErr == null) firstErr = it; Log.w(TAG, "encoder.release", it) }
        runCatching { inputSurface.release() }.exceptionOrNull()?.let { if (firstErr == null) firstErr = it; Log.w(TAG, "inputSurface.release", it) }
        return firstErr
    }
}

/**
 * Factory: open extractor + build the encoder→inputSurface→outputSurface→decoder
 * chain. Retry with the next encoder candidate on failure; on success, the
 * whole pipeline is alive and ready to step().
 */
internal object VideoPairFactory {
    private const val TAG = "VideoPair"

    fun create(
        inputPath: String,
        profile: QualityProfile,
        trimStartUs: Long,
        trimEndUs: Long,
        excludedDecoders: Set<String>,
    ): VideoPair? {
        val extractor = MediaExtractor()
        extractor.setDataSource(inputPath)
        val (trackIndex, format) = selectVideoTrack(extractor) ?: run {
            extractor.release()
            return null
        }
        extractor.selectTrack(trackIndex)

        val srcW = readDim(format, MediaFormat.KEY_WIDTH, "display-width")
        val srcH = readDim(format, MediaFormat.KEY_HEIGHT, "display-height")
        val out = computeOutputDimensions(srcW, srcH, profile.shortEdgePx)

        // Encoder output format (Surface input — no KEY_COLOR_FORMAT=YUV).
        val outFormat = MediaFormat.createVideoFormat(profile.videoCodec, out.width, out.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, profile.videoBitrateBps)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }

        val encoderCandidates = CodecSelection.selectEncoders(profile.videoCodec)
        if (encoderCandidates.isEmpty()) {
            extractor.release()
            throw CodecUnavailableException(codecMimeType = profile.videoCodec, isEncoder = true)
        }

        var encoder: MediaCodec? = null
        var inputSurface: InputSurface? = null
        var outputSurface: OutputSurface? = null
        var configured: DecoderConfig.Configured? = null
        var lastErr: Throwable? = null

        // Outer retry over encoder candidates. Each attempt rebuilds the full
        // chain (encoder → input surface → output surface → decoder) because
        // releasing the InputSurface destroys the EGL context the OutputSurface
        // depends on. Simpler than Signal's "preserve decoder, swap encoder"
        // optimisation; rare path, not worth the complexity.
        for ((i, info) in encoderCandidates.withIndex()) {
            try {
                encoder = MediaCodec.createByCodecName(info.name)
                encoder.configure(outFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)

                // createInputSurface MUST happen between configure() and start().
                val encoderSurface = encoder.createInputSurface()
                inputSurface = InputSurface(encoderSurface)
                inputSurface.makeCurrent()  // binds EGL context to this thread + encoder surface

                outputSurface = OutputSurface(viewportW = out.width, viewportH = out.height)

                // Build the decoder — its output Surface is OutputSurface.getSurface().
                configured = DecoderConfig.createVideoDecoder(
                    inputFormat = format,
                    outputSurface = outputSurface.getSurface(),
                    excludedNames = excludedDecoders,
                )

                encoder.start()
                if (i > 0) Log.w(TAG, "encoder OK with fallback ${info.name} (attempt ${i + 1}/${encoderCandidates.size})")
                break  // success — chain is ready
            } catch (e: GlPipelineException) {
                // GL failures are not codec-swappable. Release and rethrow.
                releasePartial(encoder, inputSurface, outputSurface, configured)
                extractor.release()
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "encoder ${info.name} attempt failed (${i + 1}/${encoderCandidates.size})", e)
                lastErr = e
                releasePartial(encoder, inputSurface, outputSurface, configured)
                encoder = null; inputSurface = null; outputSurface = null; configured = null
            }
        }

        if (encoder == null || inputSurface == null || outputSurface == null || configured == null) {
            extractor.release()
            throw CodecUnavailableException(codecMimeType = profile.videoCodec, isEncoder = true, cause = lastErr)
        }

        if (trimStartUs > 0) {
            extractor.seekTo(trimStartUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        }

        return VideoPair(
            extractor = extractor,
            videoTrackIndex = trackIndex,
            decoder = configured.decoder,
            encoder = encoder,
            inputSurface = inputSurface,
            outputSurface = outputSurface,
            trimStartUs = trimStartUs,
            trimEndUs = trimEndUs,
            decoderName = configured.codecName,
        )
    }

    /** Best-effort teardown of any half-built pieces from one failed encoder attempt. */
    private fun releasePartial(
        encoder: MediaCodec?,
        inputSurface: InputSurface?,
        outputSurface: OutputSurface?,
        configured: DecoderConfig.Configured?,
    ) {
        if (configured != null) {
            try { configured.decoder.stop() } catch (_: Exception) {}
            try { configured.decoder.release() } catch (_: Exception) {}
        }
        try { outputSurface?.release() } catch (_: Exception) {}
        if (encoder != null) {
            try { encoder.release() } catch (_: Exception) {}
        }
        try { inputSurface?.release() } catch (_: Exception) {}
    }

    private fun selectVideoTrack(extractor: MediaExtractor): Pair<Int, MediaFormat>? {
        for (i in 0 until extractor.trackCount) {
            val fmt = extractor.getTrackFormat(i)
            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("video/")) return i to fmt
        }
        return null
    }

    private fun readDim(format: MediaFormat, primary: String, display: String): Int {
        return if (format.containsKey(display)) format.getInteger(display) else format.getInteger(primary)
    }
}
