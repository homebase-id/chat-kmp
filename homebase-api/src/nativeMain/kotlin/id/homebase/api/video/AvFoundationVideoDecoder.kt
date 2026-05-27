package id.homebase.api.video

import id.homebase.api.foundation.toByteArray
import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.AVFoundation.AVAssetImageGenerator
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.valueWithCMTime
import platform.CoreGraphics.CGSizeMake
import platform.CoreMedia.CMTime
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMake
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.NSValue
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import kotlin.coroutines.resume

/**
 * AVFoundation primary decoder. Hardware-accelerated, ships with iOS — handles all the codecs
 * the system can play. The companion [FFmpegKitVideoDecoder] picks up containers AVFoundation
 * refuses (some MKV / legacy codecs).
 */
@OptIn(ExperimentalForeignApi::class)
internal class AvFoundationVideoDecoder : VideoDecoder {

    override suspend fun extractPosterFrame(videoPath: String): ByteArray? {
        if (!NSFileManager.defaultManager.fileExistsAtPath(videoPath)) return null

        val asset = AVURLAsset.URLAssetWithURL(NSURL.fileURLWithPath(videoPath), null)
        val generator = AVAssetImageGenerator(asset)
        generator.setAppliesPreferredTrackTransform(true)
        // Snap to nearest available frame (typically a keyframe) for speed.
        generator.setRequestedTimeToleranceBefore(CMTimeMake(Long.MAX_VALUE, 1))
        generator.setRequestedTimeToleranceAfter(CMTimeMake(Long.MAX_VALUE, 1))
        generator.setMaximumSize(CGSizeMake(POSTER_MAX_PX.toDouble(), POSTER_MAX_PX.toDouble()))

        // Aim slightly past 0 to dodge the all-black opening frame some captures have — same
        // heuristic the web <video> decoder uses.
        val target = NSValue.valueWithCMTime(CMTimeMake(POSTER_TARGET_MS, 1000))

        return suspendCancellableCoroutine { cont ->
            generator.generateCGImagesAsynchronouslyForTimes(listOf(target)) { _, image, _, _, _ ->
                val bytes = image?.let {
                    val ui = UIImage.imageWithCGImage(it)
                    UIImageJPEGRepresentation(ui, POSTER_JPEG_QUALITY)?.toByteArray()
                }
                if (cont.isActive) cont.resume(bytes)
            }
            cont.invokeOnCancellation {
                runCatching { generator.cancelAllCGImageGeneration() }
            }
        }
    }

    override fun extractThumbnailStrip(
        videoPath: String,
        durationMs: Long,
        frameCount: Int,
        targetHeightPx: Int,
    ): Flow<IndexedFrame> = channelFlow {
        if (frameCount <= 0 || durationMs <= 0L) return@channelFlow
        if (!NSFileManager.defaultManager.fileExistsAtPath(videoPath)) return@channelFlow

        val asset = AVURLAsset.URLAssetWithURL(NSURL.fileURLWithPath(videoPath), null)
        val generator = AVAssetImageGenerator(asset)
        generator.setAppliesPreferredTrackTransform(true)
        generator.setRequestedTimeToleranceBefore(CMTimeMake(Long.MAX_VALUE, 1))
        generator.setRequestedTimeToleranceAfter(CMTimeMake(Long.MAX_VALUE, 1))
        generator.setMaximumSize(CGSizeMake(0.0, targetHeightPx.toDouble()))

        val timescale = 1000
        val step = durationMs.toDouble() / frameCount
        val times = (0 until frameCount).map { i ->
            val tMs = (step * (i + 0.5)).toLong().coerceIn(0L, durationMs - 1)
            NSValue.valueWithCMTime(CMTimeMake(tMs, timescale))
        }

        var processedCount = 0
        generator.generateCGImagesAsynchronouslyForTimes(times) { requestedTime, image, _, _, _ ->
            val secs = cmTimeSecondsSafe(requestedTime)
            val idx = (secs * 1000.0 / step - 0.5).toInt().coerceIn(0, frameCount - 1)
            val tMs = (step * (idx + 0.5)).toLong()
            if (image != null) {
                val ui = UIImage.imageWithCGImage(image)
                val jpeg = UIImageJPEGRepresentation(ui, STRIP_JPEG_QUALITY)?.toByteArray()
                if (jpeg != null) {
                    trySend(IndexedFrame(idx, tMs, jpeg))
                }
            }
            processedCount++
            if (processedCount >= frameCount) close()
        }

        awaitClose {
            generator.cancelAllCGImageGeneration()
        }
    }

    companion object {
        private const val POSTER_MAX_PX = 640
        private const val POSTER_TARGET_MS = 100L
        private val POSTER_JPEG_QUALITY = VideoThumbnailQuality.POSTER_JPEG_QUALITY_0_TO_1
        private val STRIP_JPEG_QUALITY = VideoThumbnailQuality.STRIP_JPEG_QUALITY_0_TO_1
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun cmTimeSecondsSafe(time: CValue<CMTime>): Double {
    val s = CMTimeGetSeconds(time)
    return if (s.isNaN()) 0.0 else s
}
