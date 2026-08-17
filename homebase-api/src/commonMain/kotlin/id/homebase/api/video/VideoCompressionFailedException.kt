package id.homebase.api.video

/**
 * A transcode that was attempted and did not produce output. Distinct from a `null` return,
 * which means "skipped — the source already satisfies the 8-bit SDR envelope".
 *
 * Landmine this exists to disarm: `compressVideo` used to return null for BOTH cases, and
 * [VideoPayloadProcessor] turns null into "upload the source untouched". That silently shipped
 * 10-bit/HDR originals whenever ffmpeg failed or was unavailable — exactly the streams
 * receivers' hardware AVC decoders reject. Failing the send is the intended outcome: there is
 * no fallback that preserves the 8-bit guarantee.
 */
class VideoCompressionFailedException(
    val inputPath: String,
    reason: String,
    cause: Throwable? = null,
) : Exception("Video compression failed for $inputPath: $reason", cause)
