package id.homebase.api.video

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.dataWithContentsOfFile
import platform.posix.memcpy

@OptIn(ExperimentalForeignApi::class)
actual object VideoThumbnailExtractor {
    actual suspend fun extractPosterFrame(videoPath: String): ByteArray? {
        val thumbPath = FFmpegUtils.grabThumbnail(videoPath) ?: return null
        return try {
            val data = NSData.dataWithContentsOfFile(thumbPath) ?: return null
            val bytes = ByteArray(data.length.toInt())
            bytes.usePinned { pinned -> memcpy(pinned.addressOf(0), data.bytes, data.length) }
            bytes
        } catch (_: Exception) {
            null
        } finally {
            runCatching { NSFileManager.defaultManager.removeItemAtPath(thumbPath, null) }
        }
    }
}
