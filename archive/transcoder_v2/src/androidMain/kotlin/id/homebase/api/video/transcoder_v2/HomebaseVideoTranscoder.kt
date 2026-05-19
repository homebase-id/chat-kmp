package id.homebase.api.video.transcoder_v2

import android.media.MediaMuxer
import android.util.Log
import id.homebase.api.video.VideoQuality
import id.homebase.api.video.transcoder_v2.internal.AudioTrack
import id.homebase.api.video.transcoder_v2.internal.AudioTrackFactory
import id.homebase.api.video.transcoder_v2.internal.PreflightProbe
import id.homebase.api.video.transcoder_v2.internal.TranscodePump
import id.homebase.api.video.transcoder_v2.internal.VideoPair
import id.homebase.api.video.transcoder_v2.internal.VideoPairFactory
import id.homebase.api.video.transcoder_v2.internal.profile
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * Pure-Android transcoder. See SPEC.md (frozen 2026-05; §10/§11/Appendix B
 * amended for GL surface bridge) for the design. Public surface: one
 * suspend entry point + [Result] + [TrimRange]. No FFmpeg, no third-party
 * container libraries.
 *
 * Video pipeline: decoder writes to a `SurfaceTexture` backed by a
 * `GL_TEXTURE_EXTERNAL_OES` texture; a pass-through GLES 2.0 shader paints
 * the textured quad into the encoder's input `Surface` (obtained from
 * `MediaCodec.createInputSurface()`). Scaling is implicit (GL_LINEAR
 * bilinear sampling). Rotation is NOT performed in GL — it lives in the
 * container's `tkhd` matrix via `MediaMuxer.setOrientationHint`. See
 * `internal/gl/{InputSurface, OutputSurface, TextureRender}.kt`.
 *
 * HDR: on API 31+, `KEY_COLOR_TRANSFER_REQUEST = COLOR_TRANSFER_SDR_VIDEO`
 * is set on the decoder so it performs hardware tone-mapping into the
 * SurfaceTexture (verified by [internal.DecoderConfig.isToneMapEffective]).
 * On API <31 the decoder writes 10-bit-ish frames into the texture; the
 * shader passes them through as SDR — visibly off but playable, matching
 * Signal's behaviour on the same APIs.
 */
object HomebaseVideoTranscoder {

    private const val TAG = "HomebaseVideoTranscoder"

    sealed interface Result {
        /** Output file written and ready. */
        data class Transcoded(val outputPath: String) : Result

        /** Input was already within the quality envelope and no trim requested. */
        data object AlreadyOptimal : Result
    }

    data class TrimRange(val startMs: Long, val endMs: Long) {
        init { require(startMs < endMs) { "TrimRange: startMs ($startMs) must be < endMs ($endMs)" } }
    }

    suspend fun transcode(
        inputPath: String,
        outputPath: String,
        quality: VideoQuality = VideoQuality.STANDARD,
        trim: TrimRange? = null,
        onProgress: ((Float) -> Unit)? = null,
    ): Result = withContext(Dispatchers.IO) {
        val profile = quality.profile()

        // Pre-flight: probe + already-optimal check.
        val probe = PreflightProbe.probe(inputPath)
            ?: throw UnsupportedSourceException("Cannot probe input file: $inputPath")

        if (trim == null && PreflightProbe.isAlreadyOptimal(
                probe = probe,
                targetVideoBps = profile.videoBitrateBps,
                targetAudioBps = profile.audioBitrateBps,
                targetShortEdgePx = profile.shortEdgePx,
            )
        ) {
            Log.d(TAG, "input already optimal (quality=$quality, codec=${probe.videoCodec}, ${probe.widthPx}x${probe.heightPx}, ${probe.computedBitrateBps}bps)")
            return@withContext Result.AlreadyOptimal
        }

        val trimStartUs = (trim?.startMs ?: 0L) * 1_000L
        val trimEndUs = (trim?.endMs ?: 0L) * 1_000L
        val totalDurationUs = if (trim != null) (trim.endMs - trim.startMs) * 1_000L
                              else probe.durationMs * 1_000L

        // Retry-with-exclusion loop. SPEC §9.2.
        val excludedDecoders = HashSet<String>()
        var attemptsLeft = 4
        var lastException: Throwable? = null

        while (attemptsLeft-- > 0) {
            var videoPair: VideoPair? = null
            var audioPair: AudioTrack? = null
            var muxer: MediaMuxer? = null
            try {
                videoPair = VideoPairFactory.create(
                    inputPath = inputPath,
                    profile = profile,
                    trimStartUs = trimStartUs,
                    trimEndUs = trimEndUs,
                    excludedDecoders = excludedDecoders,
                ) ?: throw UnsupportedSourceException("No video track in: $inputPath")

                audioPair = AudioTrackFactory.create(
                    inputPath = inputPath,
                    profile = profile,
                    trimStartUs = trimStartUs,
                    trimEndUs = trimEndUs,
                )

                File(outputPath).parentFile?.mkdirs()
                muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

                coroutineScope {
                    val pump = TranscodePump(
                        videoPair = videoPair,
                        audioPair = audioPair,
                        muxer = muxer,
                        rotationDeg = probe.rotationDeg,
                        totalDurationUs = totalDurationUs,
                        onProgress = onProgress,
                        scope = this,
                    )
                    pump.run()
                }

                releaseAll(videoPair, audioPair, muxer)
                // Force last progress tick to 100% for callers.
                onProgress?.invoke(1f)
                return@withContext Result.Transcoded(outputPath)
            } catch (e: Throwable) {
                Log.w(TAG, "transcode attempt failed (excluded=$excludedDecoders, decoder=${videoPair?.decoderName})", e)
                lastException = e
                val name = videoPair?.decoderName
                val retryable = name != null && excludedDecoders.add(name) && isRetryableMidStreamFailure(e)
                if (!retryable) {
                    // Final teardown then rethrow.
                    releaseAll(videoPair, audioPair, muxer)
                    if (e is TranscodeException) throw e
                    throw TranscodeException(
                        message = e.message ?: "Transcode failed",
                        cause = e,
                        inputCodec = probe.videoCodec,
                        attemptedDecoder = videoPair?.decoderName,
                        inputBytes = probe.fileSizeBytes,
                        inputDurationMs = probe.durationMs,
                        isHdrInput = probe.isHdr,
                    )
                }
                Log.w(TAG, "retrying transcode with $name excluded")
                releaseAll(videoPair, audioPair, muxer)
                // Delete the half-written output so the next attempt starts clean.
                try { File(outputPath).delete() } catch (_: Exception) {}
            }
        }
        throw TranscodeException(
            message = "Transcode failed after retries",
            cause = lastException,
            inputCodec = probe.videoCodec,
            inputBytes = probe.fileSizeBytes,
            inputDurationMs = probe.durationMs,
            isHdrInput = probe.isHdr,
        )
    }

    /** SPEC §9.2 + signal-reference-notes MediaConverter:152-199. */
    private fun isRetryableMidStreamFailure(e: Throwable): Boolean {
        // GL pipeline failures are never fixed by swapping codecs — fail fast.
        if (e is GlPipelineException) return false
        var cur: Throwable? = e
        while (cur != null) {
            if (cur is GlPipelineException) return false
            if (cur is IllegalStateException) {
                val msg = cur.message.orEmpty()
                if ("frame counts should match" in msg) return true
                for (frame in cur.stackTrace) {
                    if (frame.className.startsWith("android.media.MediaCodec")) return true
                }
            }
            cur = cur.cause
        }
        return false
    }

    private fun releaseAll(
        videoPair: VideoPair?,
        audioPair: AudioTrack?,
        muxer: MediaMuxer?,
    ) {
        // SPEC §5: encoders stopped before muxer.stop() (muxer writes the moov
        // atom on stop). The pair.release() above stops encoders. Then muxer.
        runCatching { videoPair?.release() }.exceptionOrNull()?.let { Log.w(TAG, "video pair release", it) }
        runCatching { audioPair?.release() }.exceptionOrNull()?.let { Log.w(TAG, "audio pair release", it) }
        if (muxer != null) {
            // muxer.stop() throws if start() was never called — runCatching swallows.
            runCatching { muxer.stop() }
            runCatching { muxer.release() }.exceptionOrNull()?.let { Log.w(TAG, "muxer.release", it) }
        }
    }
}
