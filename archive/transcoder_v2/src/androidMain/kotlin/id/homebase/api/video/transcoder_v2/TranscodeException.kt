package id.homebase.api.video.transcoder_v2

/**
 * Base exception for the v2 transcoder. See SPEC.md §8 / §11. Diagnostic
 * fields chosen for crash-report value when triaging failed transcodes in
 * production.
 */
open class TranscodeException(
    message: String,
    cause: Throwable? = null,
    val inputCodec: String? = null,
    val attemptedDecoder: String? = null,
    val attemptedEncoder: String? = null,
    val inputBytes: Long? = null,
    val inputDurationMs: Long? = null,
    val isHdrInput: Boolean = false,
) : RuntimeException(message, cause)

/** No suitable codec on this device (after REGULAR + ALL fallback). */
class CodecUnavailableException(
    val codecMimeType: String,
    val isEncoder: Boolean,
    cause: Throwable? = null,
) : TranscodeException(
    message = "No ${if (isEncoder) "encoder" else "decoder"} available for $codecMimeType",
    cause = cause,
)

/** Input is unreadable / unsupported (no video track, corrupt container, etc.) */
class UnsupportedSourceException(
    message: String,
    cause: Throwable? = null,
) : TranscodeException(message, cause)

/**
 * HDR input — all decoder candidates failed to apply tone-mapping
 * (API 31+) or to produce viable output. See SPEC §11.
 */
class HdrDecoderUnavailableException(
    message: String,
    cause: Throwable? = null,
) : TranscodeException(message = message, cause = cause, isHdrInput = true)

/**
 * EGL or GL shader pipeline failure (context creation, shader compile,
 * surface bridging). Distinct from [CodecUnavailableException] because
 * swapping codecs never fixes a GL failure — the retry-with-exclusion
 * loop in [HomebaseVideoTranscoder] deliberately does not match this.
 */
class GlPipelineException(
    message: String,
    cause: Throwable? = null,
) : TranscodeException(message = message, cause = cause)
