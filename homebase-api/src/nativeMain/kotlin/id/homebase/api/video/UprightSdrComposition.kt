package id.homebase.api.video

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.AVFoundation.AVAsset
import platform.AVFoundation.AVAssetTrack
import platform.AVFoundation.AVAssetTrackSegment
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.AVMutableVideoComposition
import platform.AVFoundation.AVMutableVideoCompositionInstruction
import platform.AVFoundation.AVMutableVideoCompositionLayerInstruction
import platform.AVFoundation.AVVideoColorPrimaries_ITU_R_709_2
import platform.AVFoundation.AVVideoTransferFunction_ITU_R_709_2
import platform.AVFoundation.AVVideoYCbCrMatrix_ITU_R_709_2
import platform.AVFoundation.duration
import platform.AVFoundation.naturalSize
import platform.AVFoundation.nominalFrameRate
import platform.AVFoundation.preferredTransform
import platform.AVFoundation.segments
import platform.AVFoundation.setColorPrimaries
import platform.AVFoundation.setColorTransferFunction
import platform.AVFoundation.setColorYCbCrMatrix
import platform.AVFoundation.tracksWithMediaType
import platform.CoreGraphics.CGAffineTransformConcat
import platform.CoreGraphics.CGAffineTransformMakeScale
import platform.CoreGraphics.CGAffineTransformMakeTranslation
import platform.CoreGraphics.CGRectApplyAffineTransform
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.CoreMedia.CMTime
import platform.CoreMedia.CMTimeMake
import platform.CoreMedia.CMTimeRangeMake

private const val MAX_FPS = 60

internal fun AVAsset.firstVideoTrack(): AVAssetTrack? = tracksWithMediaType(AVMediaTypeVideo).firstOrNull() as? AVAssetTrack

/** The milliseconds the track has pictures for: a composition draws black outside them, such as an empty edit before a late start. */
@OptIn(ExperimentalForeignApi::class)
internal fun AVAssetTrack.shownMs(): LongRange? {
    val shown = segments.filterIsInstance<AVAssetTrackSegment>().filterNot { it.isEmpty() }.map { segment ->
        segment.timeMapping.useContents { target.start.ms() to target.start.ms() + target.duration.ms() }
    }
    val first = shown.minOfOrNull { it.first } ?: return null
    val end = shown.maxOf { it.second }
    return if (end > first) first until end else null
}

private fun CMTime.ms() = value * 1000 / timescale

@OptIn(ExperimentalForeignApi::class)
private fun AVAssetTrack.shownRect() =
    naturalSize.useContents { CGRectApplyAffineTransform(CGRectMake(0.0, 0.0, width, height), preferredTransform) }

@OptIn(ExperimentalForeignApi::class)
internal fun AVAssetTrack.uprightSize(): Pair<Int, Int> = shownRect().useContents { abs(size.width).roundToInt() to abs(size.height).roundToInt() }

internal fun AVAssetTrack.compositionFps(): Int = nominalFrameRate.roundToInt().takeIf { it > 0 }?.coerceAtMost(MAX_FPS) ?: 30

/** [track] turned upright, scaled to [width]×[height] and mapped to BT.709 SDR, at [fps]. */
@OptIn(ExperimentalForeignApi::class)
internal fun uprightSdrComposition(asset: AVAsset, track: AVAssetTrack, width: Int, height: Int, fps: Int): AVMutableVideoComposition {
    val (minX, minY, shownW, shownH) = track.shownRect().useContents { listOf(origin.x, origin.y, abs(size.width), abs(size.height)) }
    // Transforms written by ffmpeg leave out the translation that brings the turned frame back onto the canvas.
    val placed = CGAffineTransformConcat(
        CGAffineTransformConcat(track.preferredTransform, CGAffineTransformMakeTranslation(-minX, -minY)),
        CGAffineTransformMakeScale(width / shownW, height / shownH),
    )
    return AVMutableVideoComposition().apply {
        setRenderSize(CGSizeMake(width.toDouble(), height.toDouble()))
        setFrameDuration(CMTimeMake(1, fps))
        setColorPrimaries(AVVideoColorPrimaries_ITU_R_709_2)
        setColorTransferFunction(AVVideoTransferFunction_ITU_R_709_2)
        setColorYCbCrMatrix(AVVideoYCbCrMatrix_ITU_R_709_2)
        setInstructions(listOf(AVMutableVideoCompositionInstruction().apply {
            setTimeRange(CMTimeRangeMake(CMTimeMake(0, 1), asset.duration))
            setLayerInstructions(listOf(AVMutableVideoCompositionLayerInstruction.videoCompositionLayerInstructionWithAssetTrack(track).apply {
                setTransform(placed, CMTimeMake(0, 1))
            }))
        }))
    }
}
