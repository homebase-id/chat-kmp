package id.homebase.api.video

import co.touchlab.kermit.Logger
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
import platform.Foundation.NSValue
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import kotlin.coroutines.resume

/** iOS's poster and trim-strip decoder: AVFoundation, hardware-accelerated, for every format the system plays. */
@OptIn(ExperimentalForeignApi::class)
internal class AvFoundationVideoDecoder : VideoDecoder {

    override suspend fun extractPosterFrame(videoPath: String, fullSize: Boolean): ByteArray? {
        if (!NSFileManager.defaultManager.fileExistsAtPath(filePath(videoPath))) return null

        val generator = sdrGenerator(videoPath) ?: return null
        if (!fullSize) generator.setMaximumSize(CGSizeMake(POSTER_MAX_PX.toDouble(), POSTER_MAX_PX.toDouble()))

        // Aim slightly past 0 to dodge the all-black opening frame some captures have — same
        // heuristic the web <video> decoder uses.
        val target = NSValue.valueWithCMTime(CMTimeMake(POSTER_TARGET_MS, 1000))

        return suspendCancellableCoroutine { cont ->
            generator.generateCGImagesAsynchronouslyForTimes(listOf(target)) { _, image, _, _, error ->
                if (image == null) Logger.w(tag = TAG) { "no poster for $videoPath (fullSize=$fullSize): ${error?.localizedDescription}" }
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

    // AVAssetImageGenerator takes the whole batch at once; skipMask is ignored.
    override fun extractThumbnailStrip(
        videoPath: String,
        durationMs: Long,
        frameCount: Int,
        targetHeightPx: Int,
        skipMask: BooleanArray?,
    ): Flow<IndexedFrame> = channelFlow {
        if (frameCount <= 0 || durationMs <= 0L) return@channelFlow
        if (!NSFileManager.defaultManager.fileExistsAtPath(filePath(videoPath))) return@channelFlow

        val generator = sdrGenerator(videoPath) ?: return@channelFlow
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
        private const val TAG = "AvFoundationVideoDecoder"
        private const val POSTER_MAX_PX = 640
        private const val POSTER_TARGET_MS = 100L
        private val POSTER_JPEG_QUALITY = VideoThumbnailQuality.POSTER_JPEG_QUALITY_0_TO_1
        private val STRIP_JPEG_QUALITY = VideoThumbnailQuality.STRIP_JPEG_QUALITY_0_TO_1
    }
}

// Without a composition the generator fails PQ HDR10 with -12911 (on the simulator at least); a BT.709 one tone-maps it.
@OptIn(ExperimentalForeignApi::class)
internal fun sdrGenerator(videoPath: String): AVAssetImageGenerator? {
    val asset = AVURLAsset.URLAssetWithURL(fileUrl(videoPath), null)
    val track = asset.firstVideoTrack() ?: return null
    val (width, height) = track.uprightSize()
    return AVAssetImageGenerator(asset).apply {
        videoComposition = uprightSdrComposition(asset, track, width, height, track.compositionFps())
        // Snap to nearest available frame (typically a keyframe) for speed.
        setRequestedTimeToleranceBefore(CMTimeMake(Long.MAX_VALUE, 1))
        setRequestedTimeToleranceAfter(CMTimeMake(Long.MAX_VALUE, 1))
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun cmTimeSecondsSafe(time: CValue<CMTime>): Double {
    val s = CMTimeGetSeconds(time)
    return if (s.isNaN()) 0.0 else s
}
