package id.homebase.api.video

import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import platform.AVFoundation.AVAssetImageGenerator
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.valueWithCMTime
import platform.CoreGraphics.CGSizeMake
import platform.CoreMedia.CMTime
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMake
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.NSValue
import platform.Foundation.dataWithContentsOfFile
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import platform.posix.memcpy

@OptIn(ExperimentalForeignApi::class)
actual object VideoThumbnailExtractor {
    actual suspend fun extractPosterFrame(videoPath: String): ByteArray? {
        val thumbPath = FFmpegUtils.grabThumbnail(videoPath) ?: return null
        return try {
            val data = NSData.dataWithContentsOfFile(thumbPath) ?: return null
            data.toByteArray()
        } catch (_: Exception) {
            null
        } finally {
            runCatching { NSFileManager.defaultManager.removeItemAtPath(thumbPath, null) }
        }
    }

    actual fun extractThumbnailStrip(
        filePath: String,
        durationMs: Long,
        frameCount: Int,
        targetHeightPx: Int,
    ): Flow<IndexedFrame> = channelFlow {
        if (frameCount <= 0 || durationMs <= 0L) return@channelFlow
        if (!NSFileManager.defaultManager.fileExistsAtPath(filePath)) return@channelFlow

        val asset = AVURLAsset.URLAssetWithURL(NSURL.fileURLWithPath(filePath), null)
        val generator = AVAssetImageGenerator(asset)
        generator.setAppliesPreferredTrackTransform(true)
        // Snap to nearest available frame (typically a keyframe) for speed.
        generator.setRequestedTimeToleranceBefore(CMTimeMake(Long.MAX_VALUE, 1))
        generator.setRequestedTimeToleranceAfter(CMTimeMake(Long.MAX_VALUE, 1))
        generator.setMaximumSize(CGSizeMake(0.0, targetHeightPx.toDouble()))

        val timescale = 1000
        val step = durationMs.toDouble() / frameCount
        val times = (0 until frameCount).map { i ->
            val tMs = (step * (i + 0.5)).toLong().coerceIn(0L, durationMs - 1)
            NSValue.valueWithCMTime(CMTimeMake(tMs, timescale))
        }

        var emittedCount = 0
        generator.generateCGImagesAsynchronouslyForTimes(times) { requestedTime, image, _, _, _ ->
            val secs = cmTimeSecondsSafe(requestedTime)
            val idx = (secs * 1000.0 / step - 0.5).toInt().coerceIn(0, frameCount - 1)
            val tMs = (step * (idx + 0.5)).toLong()
            if (image != null) {
                val ui = UIImage.imageWithCGImage(image)
                val jpeg = UIImageJPEGRepresentation(ui, 0.6)?.toByteArray()
                if (jpeg != null) {
                    trySend(IndexedFrame(idx, tMs, jpeg))
                }
            }
            emittedCount++
            if (emittedCount >= frameCount) close()
        }

        awaitClose {
            generator.cancelAllCGImageGeneration()
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun cmTimeSecondsSafe(time: CValue<CMTime>): Double {
    val s = CMTimeGetSeconds(time)
    return if (s.isNaN()) 0.0 else s
}

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val bytes = ByteArray(length.toInt())
    bytes.usePinned { pinned -> memcpy(pinned.addressOf(0), this.bytes, length) }
    return bytes
}
