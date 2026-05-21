package id.homebase.api.video

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

// Pre-flight stub. Browser-side poster frames will need a strategy based on
// HTMLVideoElement + canvas.toBlob (or OffscreenCanvas) — out of scope for
// the pre-flight pass.
actual object VideoThumbnailExtractor {
    actual suspend fun extractPosterFrame(videoPath: String): ByteArray? = null

    actual fun extractThumbnailStrip(
        filePath: String,
        durationMs: Long,
        frameCount: Int,
        targetHeightPx: Int,
    ): Flow<IndexedFrame> = emptyFlow()
}
