package id.homebase.api.video

import co.touchlab.kermit.Logger
import id.homebase.api.foundation.toByteArray
import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
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
import kotlin.math.roundToLong

/** iOS's poster and trim-strip decoder: AVFoundation, hardware-accelerated, for every format the system plays. */
@OptIn(ExperimentalForeignApi::class)
internal class AvFoundationVideoDecoder : VideoDecoder {

    override suspend fun extractPosterFrame(videoPath: String, fullSize: Boolean): ByteArray? {
        if (!NSFileManager.defaultManager.fileExistsAtPath(filePath(videoPath))) return null

        val (generator, shownMs) = withContext(Dispatchers.IO) { sdrGenerator(videoPath) } ?: return null
        if (!fullSize) generator.setMaximumSize(CGSizeMake(POSTER_MAX_PX.toDouble(), POSTER_MAX_PX.toDouble()))

        // Aim slightly past the first picture to dodge the all-black opening frame some captures have — same
        // heuristic the web <video> decoder uses.
        val target = NSValue.valueWithCMTime(CMTimeMake((shownMs.first + POSTER_TARGET_MS).coerceAtMost(shownMs.last), 1000))

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

        val (generator, shownMs) = withContext(Dispatchers.IO) { sdrGenerator(videoPath) } ?: return@channelFlow
        generator.setMaximumSize(CGSizeMake(0.0, targetHeightPx.toDouble()))

        val step = durationMs.toDouble() / frameCount
        val requestedMs = LongArray(frameCount) { i -> (step * (i + 0.5)).toLong().coerceIn(0L, durationMs - 1).coerceIn(shownMs) }
        // Clamped slots can share a time, so each answer goes to the first slot still waiting for it.
        val waiting = (0 until frameCount).toMutableList()

        var processedCount = 0
        generator.generateCGImagesAsynchronouslyForTimes(requestedMs.map { NSValue.valueWithCMTime(CMTimeMake(it, 1000)) }) { requestedTime, image, _, _, _ ->
            val ms = (cmTimeSecondsSafe(requestedTime) * 1000).roundToLong()
            val idx = waiting.firstOrNull { requestedMs[it] == ms } ?: waiting.first()
            waiting.remove(idx)
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
// Blocks while AVFoundation loads the track, so call it off the main thread.
@OptIn(ExperimentalForeignApi::class)
internal fun sdrGenerator(videoPath: String): Pair<AVAssetImageGenerator, LongRange>? {
    val asset = AVURLAsset.URLAssetWithURL(fileUrl(videoPath), null)
    val track = asset.firstVideoTrack() ?: return null
    val (width, height) = track.uprightSize()
    if (width <= 0 || height <= 0) return null
    val shownMs = track.shownMs() ?: return null
    return AVAssetImageGenerator(asset).apply {
        videoComposition = uprightSdrComposition(asset, track, width, height, track.compositionFps())
        // Snap to nearest available frame (typically a keyframe) for speed.
        setRequestedTimeToleranceBefore(CMTimeMake(Long.MAX_VALUE, 1))
        setRequestedTimeToleranceAfter(CMTimeMake(Long.MAX_VALUE, 1))
    } to shownMs
}

@OptIn(ExperimentalForeignApi::class)
private fun cmTimeSecondsSafe(time: CValue<CMTime>): Double {
    val s = CMTimeGetSeconds(time)
    return if (s.isNaN()) 0.0 else s
}
