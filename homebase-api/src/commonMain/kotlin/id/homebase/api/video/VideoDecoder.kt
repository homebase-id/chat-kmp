package id.homebase.api.video

import kotlinx.coroutines.flow.Flow

/** A single extracted frame with its source position in the video. */
data class IndexedFrame(
    val index: Int,
    val timeMs: Long,
    val jpegBytes: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is IndexedFrame) return false
        return index == other.index && timeMs == other.timeMs &&
            jpegBytes.contentEquals(other.jpegBytes)
    }

    override fun hashCode(): Int {
        var r = index
        r = 31 * r + timeMs.hashCode()
        r = 31 * r + jpegBytes.contentHashCode()
        return r
    }
}

/**
 * One platform's decode path for poster frames and trim-scrubber filmstrips.
 *
 * Implementations come in two flavours:
 * - "Primary" decoders use the OS-native pipeline (MediaCodec / AVFoundation /
 *   `<video>`+`<canvas>`). Hardware-accelerated and ships with the OS.
 * - "Fallback" decoders use ffmpeg (FFmpegKit on iOS, ffmpeg.wasm on web,
 *   ffmpeg subprocess on JVM). Heavier, broader codec coverage.
 *
 * [TieredVideoDecoder] composes a primary and an optional fallback into a single
 * [VideoDecoder] that runs the fallback only when the primary fails or produces
 * a partial result. Each platform's `platformVideoDecoder()` factory assembles the
 * right combination — see [VideoThumbnailService].
 *
 * The [videoPath] string is platform-specific by necessity (see the KDoc on
 * [VideoThumbnailService]); it stays a `String` to mesh with the rest of the
 * `FFmpegUtils` video pipeline.
 */
interface VideoDecoder {
    /** Extract a poster frame. Returns JPEG bytes or null if extraction failed. */
    suspend fun extractPosterFrame(videoPath: String): ByteArray?

    /**
     * Extract [frameCount] thumbnails evenly spaced across `[0, durationMs]`. Frames are
     * emitted onto the returned [Flow] as they become ready (out-of-order is allowed), so
     * the UI paints the trim bar progressively.
     *
     * Each frame is decoded at the closest sync (keyframe) to its target time and scaled to
     * approximately [targetHeightPx] tall, preserving aspect ratio. JPEG-encoded.
     */
    fun extractThumbnailStrip(
        videoPath: String,
        durationMs: Long,
        frameCount: Int,
        targetHeightPx: Int,
    ): Flow<IndexedFrame>
}
