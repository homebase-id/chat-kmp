package id.homebase.api.video

import co.touchlab.kermit.Logger
import id.homebase.api.image.ns
import kotlin.math.roundToLong
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.useContents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import platform.AVFoundation.AVAssetTrack
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.duration
import platform.AVFoundation.formatDescriptions
import platform.AVFoundation.naturalSize
import platform.AVFoundation.tracksWithMediaType
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFRetain
import platform.CoreFoundation.CFStringRef
import platform.CoreMedia.CMFormatDescriptionGetExtension
import platform.CoreMedia.CMFormatDescriptionGetMediaSubType
import platform.CoreMedia.CMFormatDescriptionRef
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.kCMFormatDescriptionExtension_BitsPerComponent
import platform.CoreMedia.kCMFormatDescriptionExtension_TransferFunction
import platform.CoreMedia.kCMFormatDescriptionTransferFunction_ITU_R_2100_HLG
import platform.CoreMedia.kCMFormatDescriptionTransferFunction_SMPTE_ST_2084_PQ
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSFileManager
import platform.Foundation.NSNumber
import platform.Foundation.NSURL

internal actual fun platformVideoProber(): VideoProber = IosVideoProber

/** AVFoundation's headers only: no pass over the media. */
@OptIn(ExperimentalForeignApi::class)
internal object IosVideoProber : VideoProber {
    private const val TAG = "IosVideoProber"

    override suspend fun getDurationMs(inputPath: String): Long = withContext(Dispatchers.IO) {
        val seconds = CMTimeGetSeconds(AVURLAsset.URLAssetWithURL(fileUrl(inputPath), null).duration)
        if (seconds.isNaN() || seconds <= 0) 0L else (seconds * 1000.0).roundToLong()
    }

    override suspend fun getFfmpegVersion(): String? = null

    // Bit depth and HDR stay null when the track doesn't say: that is "unknown", not 8-bit SDR.
    override suspend fun probeVideo(inputPath: String): VideoTrackInfo? = withContext(Dispatchers.IO) {
        if (!NSFileManager.defaultManager.fileExistsAtPath(filePath(inputPath))) return@withContext null
        val track = AVURLAsset.URLAssetWithURL(fileUrl(inputPath), null)
            .tracksWithMediaType(AVMediaTypeVideo).firstOrNull() as? AVAssetTrack ?: return@withContext null
        val (width, height) = track.naturalSize.useContents { width.toInt() to height.toInt() }
        if (width <= 0 || height <= 0) return@withContext null
        val info = track.retainedFormat()?.let { desc ->
            try {
                Triple(codecName(CMFormatDescriptionGetMediaSubType(desc)), bitDepth(desc), isHdr(desc))
            } finally {
                CFRelease(desc)
            }
        }
        if (info == null) Logger.w(tag = TAG) { "no format description for $inputPath" }
        VideoTrackInfo(info?.first, width, height, info?.second, info?.third)
    }

    // AVFoundation opens MP4, MOV, M4V, 3GP and AVI; MKV and WebM it refuses.
    override fun canRead(contentType: String): Boolean =
        contentType.startsWith("video/") && AVURLAsset.isPlayableExtendedMIMEType(contentType)

    private fun codecName(fourCc: UInt): String? = when (fourCc.toFourCc()) {
        "avc1", "avc3" -> "h264"
        "hvc1", "hev1" -> "hevc"
        "av01" -> "av1"
        else -> null
    }

    private fun bitDepth(desc: CMFormatDescriptionRef): Int? =
        (extension(desc, kCMFormatDescriptionExtension_BitsPerComponent) as? NSNumber)?.intValue

    private fun isHdr(desc: CMFormatDescriptionRef): Boolean? {
        val transfer = extension(desc, kCMFormatDescriptionExtension_TransferFunction) as? String ?: return null
        return transfer == kCMFormatDescriptionTransferFunction_ITU_R_2100_HLG.ns || transfer == kCMFormatDescriptionTransferFunction_SMPTE_ST_2084_PQ.ns
    }

    private fun extension(desc: CMFormatDescriptionRef, key: CFStringRef?): Any? =
        CMFormatDescriptionGetExtension(desc, key)?.let { CFBridgingRelease(CFRetain(it)) }

    private fun UInt.toFourCc(): String =
        charArrayOf((this shr 24).toInt().toChar(), (this shr 16 and 0xFFu).toInt().toChar(), (this shr 8 and 0xFFu).toInt().toChar(), (this and 0xFFu).toInt().toChar()).concatToString()
}

/** The track's first format description, retained: release it with CFRelease. */
@OptIn(ExperimentalForeignApi::class)
internal fun AVAssetTrack.retainedFormat(): CMFormatDescriptionRef? = formatDescriptions.firstOrNull()?.let { CFBridgingRetain(it) }?.reinterpret()

internal fun filePath(path: String): String = if (path.startsWith("file://")) NSURL.URLWithString(path)?.path ?: path else path

internal fun fileUrl(path: String): NSURL = NSURL.fileURLWithPath(filePath(path))
