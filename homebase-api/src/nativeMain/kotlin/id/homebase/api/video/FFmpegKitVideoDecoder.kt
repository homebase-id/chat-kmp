package id.homebase.api.video

import id.homebase.api.foundation.toByteArray
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUserDomainMask
import platform.Foundation.NSUUID
import platform.Foundation.dataWithContentsOfFile
import kotlin.math.roundToInt

/**
 * FFmpegKit fallback decoder for iOS. Engaged when [AvFoundationVideoDecoder] refuses the
 * container — typically some MKV variants or legacy codecs that AVFoundation doesn't support.
 *
 * Strip emission is progressive: the ffmpeg invocation is driven via
 * [FFmpegKitBridge.executeFFmpegAsync] while a polling loop drains newly-written JPEGs from
 * the per-extraction cache directory, mirroring the JVM decoder's approach. Flow cancellation
 * cancels the bridge session and clears the cache dir.
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

    // `skipMask` is ignored: this decoder runs ffmpeg as a single invocation with one fps
    // filter that emits all N frames. Skipping a specific index would mean a separate ffmpeg
    // run per gap — way more expensive than the wasted decodes we'd avoid. See the KDoc on
    // VideoDecoder.extractThumbnailStrip.
    override fun extractThumbnailStrip(
        videoPath: String,
        durationMs: Long,
        frameCount: Int,
        targetHeightPx: Int,
        skipMask: BooleanArray?,
    ): Flow<IndexedFrame> = channelFlow {
        if (frameCount <= 0 || durationMs <= 0L) return@channelFlow
        val fileManager = NSFileManager.defaultManager
        if (!fileManager.fileExistsAtPath(videoPath)) return@channelFlow

        val outDir = "${cacheDir()}/vts_${NSUUID.UUID().UUIDString}"
        fileManager.createDirectoryAtPath(outDir, true, null, null)

        val completion = CompletableDeferred<FFmpegResult>()
        val emitted = HashSet<Int>()
        val step = durationMs.toDouble() / frameCount

        try {
            val durationS = durationMs / 1000.0
            val fps = frameCount.toDouble() / durationS.coerceAtLeast(0.001)
            val pattern = "$outDir/f_%04d.jpg"
            // Use the arg-list variant — `executeWithArguments` skips FFmpegKit's shell-style
            // argv parser, so `videoPath` and `pattern` pass through verbatim without any
            // quote-escaping risk. Mirrors what FFmpegSubprocessVideoDecoder on JVM gets for
            // free from ProcessBuilder(List<String>).
            val args = listOf(
                "-y",
                "-loglevel", "error",
                "-i", videoPath,
                "-vf", "fps=${formatLocaleSafe(fps)},scale=-2:${targetHeightPx}",
                "-frames:v", frameCount.toString(),
                "-q:v", VideoThumbnailQuality.STRIP_FFMPEG_QSCALE.toString(),
                pattern,
            )

            bridge.executeFFmpegAsyncArgs(
                args = args,
                onProgress = { /* unused — we drive emission via dir polling */ },
                onComplete = { result -> completion.complete(result) },
            )

            // Poll while ffmpeg runs. Same idiom as FFmpegSubprocessVideoDecoder on JVM —
            // gives the trim scrubber progressive emissions instead of a single end-of-run dump.
            while (!completion.isCompleted && isActive) {
                drainNewFrames(outDir, emitted, frameCount, step) { trySend(it) }
                delay(POLL_INTERVAL_MS)
            }
            // Final sweep — ffmpeg may have written files just before exiting.
            drainNewFrames(outDir, emitted, frameCount, step) { trySend(it) }
        } finally {
            // If the consumer cancelled mid-run, tear the ffmpeg session down too. Bridge-wide
            // cancel is acceptable for the current single-job upload flow (see FFmpegKitBridge
            // KDoc). If concurrent ffmpeg jobs are ever introduced, swap to per-session cancel.
            if (!completion.isCompleted) {
                runCatching { bridge.cancelAllFFmpegSessions() }
            }
            // Wait for ffmpeg's worker to actually settle before we wipe the dir — otherwise a
            // last-millisecond flush from ffmpeg writes a JPEG into a folder we're already
            // deleting, leaving an orphan file in the cache dir until the next CacheSweeper run.
            // Bounded wait: if cancellation doesn't take effect, give up after the timeout and
            // delete anyway (the orphan is annoying, but blocking finally{} forever is worse).
            //
            // Explicit try/catch (not runCatching) because runCatching wrapping a suspend call
            // hits the Kotlin/Native compiler bug "Lowering ReturnsInsertion: phases [Autobox,
            // Coroutines] are required, but not satisfied". Same foot-gun called out in
            // CLAUDE.md and AttachmentHandler.kt:167-171. CancellationException is swallowed
            // here on purpose — we're already in finally{} cleanup; letting it through would
            // abort the dir teardown below and orphan all of outDir.
            try {
                withTimeoutOrNull(TEARDOWN_WAIT_MS) { completion.await() }
            } catch (_: Throwable) { }
            runCatching {
                val contents = fileManager.contentsOfDirectoryAtPath(outDir, null) ?: emptyList<Any>()
                for (name in contents) {
                    fileManager.removeItemAtPath("$outDir/$name", null)
                }
                fileManager.removeItemAtPath(outDir, null)
            }
        }
    }.flowOn(Dispatchers.IO)

    private fun drainNewFrames(
        outDir: String,
        emitted: HashSet<Int>,
        frameCount: Int,
        stepMs: Double,
        emit: (IndexedFrame) -> Unit,
    ) {
        val fileManager = NSFileManager.defaultManager
        val contents = fileManager.contentsOfDirectoryAtPath(outDir, null) ?: return
        // ffmpeg's fps filter writes 1-based sequence numbers — sort for stable ordering.
        val names = contents.mapNotNull { it as? String }.sorted()
        for (name in names) {
            if (!name.startsWith("f_") || !name.endsWith(".jpg")) continue
            val n = name.removePrefix("f_").removeSuffix(".jpg").toIntOrNull() ?: continue
            val zeroBased = n - 1
            if (zeroBased !in 0 until frameCount) continue
            if (zeroBased in emitted) continue
            val path = "$outDir/$name"
            val bytes = NSData.dataWithContentsOfFile(path)?.toByteArray() ?: continue
            if (bytes.size < MIN_JPEG_BYTES) continue
            emitted.add(zeroBased)
            val timeMs = (stepMs * (zeroBased + 0.5)).toLong()
            emit(IndexedFrame(zeroBased, timeMs, bytes))
        }
    }

    private fun cacheDir(): String {
        val paths = NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true)
        return paths.firstOrNull() as? String ?: NSTemporaryDirectory()
    }

    private fun formatLocaleSafe(value: Double): String {
        val whole = value.toLong()
        val frac = ((value - whole) * 1_000_000).roundToInt().coerceAtLeast(0)
        return "$whole.${frac.toString().padStart(6, '0')}"
    }

    companion object {
        private const val POLL_INTERVAL_MS = 40L
        private const val MIN_JPEG_BYTES = 16

        /**
         * Upper bound on how long we wait for ffmpeg's worker to flush after we've signalled
         * cancellation, before tearing down the cache dir anyway. 500 ms is well above the
         * observed flush time on Apple silicon (single-digit ms for a 10-frame strip) and
         * still short enough that a stuck session doesn't lock up the flow's finally{}.
         */
        private const val TEARDOWN_WAIT_MS = 500L
    }
}
