package id.homebase.api.video

import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive

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

    actual fun extractThumbnailStrip(
        filePath: String,
        durationMs: Long,
        frameCount: Int,
        targetHeightPx: Int,
    ): Flow<IndexedFrame> = channelFlow {
        if (frameCount <= 0 || durationMs <= 0L) return@channelFlow
        if (!FFmpegBinaryManager.isAvailable()) return@channelFlow
        if (!File(filePath).exists()) return@channelFlow

        val outputDir = File(
            System.getProperty("java.io.tmpdir"),
            "vts_${UUID.randomUUID()}",
        ).apply { mkdirs() }

        try {
            // One ffmpeg invocation samples N evenly-spaced frames. Using fps based on
            // a safe denominator and a strict frames cap keeps it predictable on both
            // long and short videos.
            val durationS = durationMs / 1000.0
            val fps = frameCount.toDouble() / durationS.coerceAtLeast(0.001)
            val command = listOf(
                FFmpegBinaryManager.ffmpegPath(),
                "-y",
                "-loglevel", "error",
                "-i", filePath,
                "-vf", "fps=${formatLocaleSafe(fps)},scale=-2:${targetHeightPx}",
                "-frames:v", frameCount.toString(),
                "-q:v", "5",
                File(outputDir, "f_%04d.jpg").absolutePath,
            )

            val process = ProcessBuilder(command).redirectErrorStream(true).start()

            // Watch the dir while ffmpeg runs; emit frames as they appear.
            val emitted = HashSet<Int>()
            val step = durationMs.toDouble() / frameCount

            while (isActive && process.isAlive) {
                drainNewFrames(outputDir, emitted, frameCount, step) { frame ->
                    trySend(frame)
                }
                Thread.sleep(40)
            }
            process.waitFor(2, TimeUnit.MINUTES)
            // Final sweep in case ffmpeg wrote files just before exiting.
            drainNewFrames(outputDir, emitted, frameCount, step) { frame -> trySend(frame) }
        } catch (_: Exception) {
            // swallow — caller treats missing frames as "still loading"
        } finally {
            runCatching {
                outputDir.listFiles()?.forEach { it.delete() }
                outputDir.delete()
            }
        }
    }.flowOn(Dispatchers.IO)

    private fun formatLocaleSafe(value: Double): String {
        // Avoid Locale-dependent decimal separators (",").
        val whole = value.toLong()
        val frac = ((value - whole) * 1_000_000).toLong().coerceAtLeast(0)
        return "$whole.${frac.toString().padStart(6, '0')}"
    }

    private fun drainNewFrames(
        outputDir: File,
        emitted: HashSet<Int>,
        frameCount: Int,
        stepMs: Double,
        emit: (IndexedFrame) -> Unit,
    ) {
        val files = outputDir.listFiles { f -> f.name.startsWith("f_") && f.name.endsWith(".jpg") }
            ?: return
        for (file in files.sortedBy { it.name }) {
            // f_0001.jpg → index 0
            val n = file.nameWithoutExtension.removePrefix("f_").toIntOrNull() ?: continue
            val zeroBased = n - 1
            if (zeroBased !in 0 until frameCount) continue
            if (zeroBased in emitted) continue
            // ffmpeg writes the file before flushing; skip empties to avoid corrupt JPEG
            if (file.length() < 16) continue
            val bytes = try { file.readBytes() } catch (_: Exception) { continue }
            if (bytes.size < 16) continue
            emitted.add(zeroBased)
            val timeMs = (stepMs * (zeroBased + 0.5)).toLong()
            emit(IndexedFrame(zeroBased, timeMs, bytes))
        }
    }
}
