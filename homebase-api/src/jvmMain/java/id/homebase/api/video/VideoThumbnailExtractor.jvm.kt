package id.homebase.api.video

import java.io.File

actual object VideoThumbnailExtractor {
    actual suspend fun extractPosterFrame(videoPath: String): ByteArray? {
        val thumbPath = FFmpegUtils.grabThumbnail(videoPath) ?: return null
        return try {
            File(thumbPath).readBytes()
        } catch (_: Exception) {
            null
        } finally {
            runCatching { File(thumbPath).delete() }
        }
    }
}
