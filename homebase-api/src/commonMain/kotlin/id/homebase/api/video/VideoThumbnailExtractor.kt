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
 * Fast poster-frame extractor for videos.
 *
 * Returns JPEG bytes directly so callers don't need to dance through a temp file +
 * readFileBytes + delete. Each platform picks the cheapest pipeline available; the
 * expensive [FFmpegUtils.grabThumbnail] path is only used as a last resort.
 */
expect object VideoThumbnailExtractor {
    /**
     * Extract a poster frame from [videoPath]. On Android this may be a `content://` URI
     * (preferred — avoids the full file copy that [FFmpegUtils.grabThumbnail] requires).
     * Returns JPEG bytes or null if extraction failed.
     */
    suspend fun extractPosterFrame(videoPath: String): ByteArray?

    /**
     * Extract [frameCount] thumbnails evenly spaced across `[0, durationMs]`. Frames are
     * emitted onto the returned [Flow] as they become ready (out-of-order is allowed),
     * letting the UI paint the trim bar progressively.
     *
     * Each frame is decoded at the closest sync (keyframe) to its target time and scaled to
     * approximately [targetHeightPx] tall, preserving aspect ratio. JPEG-encoded.
     */
    fun extractThumbnailStrip(
        filePath: String,
        durationMs: Long,
        frameCount: Int,
        targetHeightPx: Int,
    ): Flow<IndexedFrame>
}
