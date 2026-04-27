package id.homebase.api.video

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
}
