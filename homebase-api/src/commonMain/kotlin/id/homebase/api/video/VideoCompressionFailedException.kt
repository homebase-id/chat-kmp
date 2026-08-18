package id.homebase.api.video

/**
 * A transcode was attempted and produced no output.
 *
 * Landmine this exists to disarm: `compressVideo` used to return null both for "skipped, the
 * source is already fine" and for "ffmpeg failed", and the caller turned null into "upload the
 * source untouched". That silently shipped 10-bit/HDR originals whenever ffmpeg failed or was
 * unavailable — the streams receivers' hardware AVC decoders reject. There is no fallback that
 * preserves the 8-bit guarantee, so the send fails instead.
 */
class VideoCompressionFailedException(
    val inputPath: String,
    reason: String,
    cause: Throwable? = null,
) : Exception("Video compression failed for $inputPath: $reason", cause)
