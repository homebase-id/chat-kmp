@file:OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)

package id.homebase.api.video

import id.homebase.api.file.systemFileSystem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import okio.Path.Companion.toPath

/**
 * ffmpeg.wasm fallback decoder. Engaged when [BrowserVideoDecoder] can't decode the codec
 * (some HEVC variants on Firefox, etc.). Triggers a ~22 MB core download on first use — but
 * only on the fallback path, so happy-path uploads pay nothing.
 */
internal class FFmpegWasmVideoDecoder : VideoDecoder {

    override suspend fun extractPosterFrame(videoPath: String): ByteArray? {
        val bytes = readBytes(videoPath) ?: return null
        FFmpegBridge.writeFile(MEMFS_INPUT, bytes)
        val status = FFmpegBridge.exec(
            listOf(
                "-y", "-loglevel", "error",
                "-i", MEMFS_INPUT,
                "-ss", "0.1",
                "-frames:v", "1",
                "-q:v", VideoThumbnailQuality.POSTER_FFMPEG_QSCALE.toString(),
                MEMFS_POSTER,
            )
        )
        val out = if (status == 0) runCatching { FFmpegBridge.readFile(MEMFS_POSTER) }.getOrNull() else null
        FFmpegBridge.deleteFile(MEMFS_INPUT)
        FFmpegBridge.deleteFile(MEMFS_POSTER)
        return out?.takeIf { it.size >= 16 }
    }

    // `skipMask` is ignored: single ffmpeg.wasm invocation per call. Same reasoning as the
    // other ffmpeg decoders — see VideoDecoder.extractThumbnailStrip KDoc.
    override fun extractThumbnailStrip(
        videoPath: String,
        durationMs: Long,
        frameCount: Int,
        targetHeightPx: Int,
        skipMask: BooleanArray?,
    ): Flow<IndexedFrame> = channelFlow {
        if (frameCount <= 0 || durationMs <= 0L) return@channelFlow
        val bytes = readBytes(videoPath) ?: return@channelFlow

        FFmpegBridge.writeFile(MEMFS_INPUT, bytes)
        try {
            val durationS = durationMs / 1000.0
            val fps = frameCount.toDouble() / durationS.coerceAtLeast(0.001)
            // Same command-line shape as the JVM decoder: one ffmpeg run, fps filter, strict cap.
            val args = listOf(
                "-y", "-loglevel", "error",
                "-i", MEMFS_INPUT,
                "-vf", "fps=${formatLocaleSafe(fps)},scale=-2:${targetHeightPx}",
                "-frames:v", frameCount.toString(),
                "-q:v", VideoThumbnailQuality.STRIP_FFMPEG_QSCALE.toString(),
                MEMFS_STRIP_PATTERN,
            )
            val status = FFmpegBridge.exec(args)
            if (status != 0) return@channelFlow

            val step = durationMs.toDouble() / frameCount
            for (i in 0 until frameCount) {
                val name = "thumb_${(i + 1).toString().padStart(4, '0')}.jpg"
                val jpeg = runCatching { FFmpegBridge.readFile(name) }.getOrNull() ?: continue
                FFmpegBridge.deleteFile(name)
                if (jpeg.size < 16) continue
                val timeMs = (step * (i + 0.5)).toLong()
                trySend(IndexedFrame(i, timeMs, jpeg))
            }
        } finally {
            FFmpegBridge.deleteFile(MEMFS_INPUT)
            // Belt and braces: any unread strip slots get reaped too.
            for (i in 0 until frameCount) {
                val name = "thumb_${(i + 1).toString().padStart(4, '0')}.jpg"
                runCatching { FFmpegBridge.deleteFile(name) }
            }
        }
    }

    private fun readBytes(path: String): ByteArray? =
        runCatching { systemFileSystem.read(path.toPath()) { readByteArray() } }.getOrNull()

    private fun formatLocaleSafe(value: Double): String {
        val whole = value.toLong()
        val frac = ((value - whole) * 1_000_000).toLong().coerceAtLeast(0)
        return "$whole.${frac.toString().padStart(6, '0')}"
    }

    companion object {
        private const val MEMFS_INPUT = "thumb_input.mp4"
        private const val MEMFS_POSTER = "thumb_poster.jpg"
        private const val MEMFS_STRIP_PATTERN = "thumb_%04d.jpg"
    }
}
