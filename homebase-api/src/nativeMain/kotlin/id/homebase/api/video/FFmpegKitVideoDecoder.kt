package id.homebase.api.video

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUserDomainMask
import platform.Foundation.NSUUID
import platform.Foundation.dataWithContentsOfFile
import platform.posix.memcpy
import kotlin.math.roundToInt

/**
 * FFmpegKit fallback decoder for iOS. Engaged when [AvFoundationVideoDecoder] refuses the
 * container — typically some MKV variants or legacy codecs that AVFoundation doesn't support.
 */
@OptIn(ExperimentalForeignApi::class)
class FFmpegKitVideoDecoder : VideoDecoder {

    private val bridge: FFmpegKitBridge
        get() = FFmpegKitBridgeHolder.getBridge()

    override suspend fun extractPosterFrame(videoPath: String): ByteArray? {
        val thumbPath = FFmpegUtils.grabThumbnail(videoPath) ?: return null
        return try {
            NSData.dataWithContentsOfFile(thumbPath)?.toByteArray()
        } catch (_: Exception) {
            null
        } finally {
            runCatching { NSFileManager.defaultManager.removeItemAtPath(thumbPath, null) }
        }
    }

    override fun extractThumbnailStrip(
        videoPath: String,
        durationMs: Long,
        frameCount: Int,
        targetHeightPx: Int,
    ): Flow<IndexedFrame> = channelFlow {
        if (frameCount <= 0 || durationMs <= 0L) return@channelFlow
        val fileManager = NSFileManager.defaultManager
        if (!fileManager.fileExistsAtPath(videoPath)) return@channelFlow

        val outDir = "${cacheDir()}/vts_${NSUUID.UUID().UUIDString}"
        fileManager.createDirectoryAtPath(outDir, true, null, null)

        try {
            val durationS = durationMs / 1000.0
            val fps = frameCount.toDouble() / durationS.coerceAtLeast(0.001)
            val pattern = "$outDir/f_%04d.jpg"
            val command = "-y -loglevel error -i \"$videoPath\" " +
                "-vf \"fps=${formatLocaleSafe(fps)},scale=-2:${targetHeightPx}\" " +
                "-frames:v $frameCount -q:v 5 \"$pattern\""

            val result = bridge.executeFFmpeg(command)
            if (!result.isSuccess) return@channelFlow

            val step = durationMs.toDouble() / frameCount
            for (i in 0 until frameCount) {
                // ffmpeg writes 1-based names from the fps filter.
                val path = "$outDir/f_${(i + 1).toString().padStart(4, '0')}.jpg"
                if (!fileManager.fileExistsAtPath(path)) continue
                val bytes = NSData.dataWithContentsOfFile(path)?.toByteArray() ?: continue
                if (bytes.size < 16) continue
                val timeMs = (step * (i + 0.5)).toLong()
                trySend(IndexedFrame(i, timeMs, bytes))
            }
        } finally {
            runCatching {
                val contents = fileManager.contentsOfDirectoryAtPath(outDir, null) ?: emptyList<Any>()
                for (name in contents) {
                    fileManager.removeItemAtPath("$outDir/$name", null)
                }
                fileManager.removeItemAtPath(outDir, null)
            }
        }
    }.flowOn(Dispatchers.IO)

    private fun cacheDir(): String {
        val paths = NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true)
        return paths.firstOrNull() as? String ?: NSTemporaryDirectory()
    }

    private fun formatLocaleSafe(value: Double): String {
        val whole = value.toLong()
        val frac = ((value - whole) * 1_000_000).roundToInt().coerceAtLeast(0)
        return "$whole.${frac.toString().padStart(6, '0')}"
    }
}

@OptIn(ExperimentalForeignApi::class)
internal fun NSData.toByteArray(): ByteArray {
    val bytes = ByteArray(length.toInt())
    if (length.toInt() > 0) {
        bytes.usePinned { pinned -> memcpy(pinned.addressOf(0), this.bytes, length) }
    }
    return bytes
}
