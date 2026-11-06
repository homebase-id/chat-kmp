package id.homebase.api.video

import id.homebase.api.client.KeyHeader
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Marks heavy video work nobody is waiting on: it stops for any that someone is. */
object BackgroundVideoWork : AbstractCoroutineContextElement(Key) {
    object Key : CoroutineContext.Key<BackgroundVideoWork>
}

/** Thrown to [BackgroundVideoWork] that stopped for other work. It hasn't failed; run it again later. */
class VideoWorkYieldedException : Exception("gave way to video work someone is waiting on")

/**
 * Singleton entry point for video compression, segmentation, and metadata probes
 * — the symmetric counterpart to [VideoThumbnailService] on the thumbnail side.
 *
 * Consumers see one generic shape ([VideoCompressor] + [VideoProber]) instead of
 * the low-level [FFmpegUtils] `expect object`. Today every platform compresses
 * via ffmpeg, so this simply delegates to the ffmpeg-backed [FFmpegVideoCompressor]
 * / [FFmpegVideoProber]; if a non-ffmpeg native backend ever appears, swap the
 * delegates here (or introduce a per-platform factory) without touching callers.
 *
 * `FFmpegUtils` remains the low-level backend and still owns the thumbnail-grab
 * helper (`grabThumbnail`) and the actuals' internal probes.
 *
 * **Concurrency: one heavy op at a time — enforced here.** The backend is NOT safe for
 * concurrent heavy operations:
 * - **Web (ffmpeg.wasm):** a single shared ffmpeg instance with fixed MEMFS scratch
 *   names (`input.mp4`, `index.m3u8`, …) — two simultaneous `compress`/`segment*` calls
 *   clobber each other's files.
 * - **iOS (FFmpegKit):** a job's coroutine-cancellation tears down its own session; if two
 *   heavy jobs overlap, the bookkeeping and scratch dirs (`compressed_<id>.mp4`,
 *   `hls_<id>/`) can collide.
 * - **Android (FFmpegKit):** the engine isn't reentrant, so overlapping sessions can crash the
 *   process. A cancelled session counts until it has really stopped.
 * - **JVM:** parallel transcodes just thrash CPU/memory for no throughput gain.
 *
 * Rather than rely on every caller (the full-screen attachment editor, multi-video send,
 * Moments, Chat) to remember to serialize, this service guards the heavy ops — [compress],
 * [segment], [segmentAndEncrypt], [remuxHlsToMp4], [transcode] — behind a single [Mutex]: a second call
 * waits until the first completes (and `withLock` is cancellation-aware, so a waiter that's
 * cancelled unblocks the queue cleanly). The cheap probes ([getDurationMs],
 * [getFfmpegVersion]) and [cacheInputVideo] are unguarded — they're fine to call freely.
 *
 * Note: this mutex serializes heavy compress/segment ops against *each other*. It does NOT
 * cover [VideoThumbnailService]'s FFmpegKit sessions, which may still run concurrently —
 * those are isolated via per-session cancel on iOS, so a compress/segment cancel never tears
 * a thumbnail session down (and vice versa).
 */
object VideoCompressionService : VideoCompressor, VideoProber {

    // Delegates are `var` only so tests can substitute fakes; production never reassigns them.
    internal var compressor: VideoCompressor = FFmpegVideoCompressor
    internal var probe: VideoProber = FFmpegVideoProber

    /** Serializes the heavy ffmpeg ops so two never run on the shared backend at once. */
    private val heavyOpLock = Mutex()

    private val waitingForeground = atomic(0)
    private val runningBackground = atomic<Job?>(null)

    private suspend fun <T> heavy(op: suspend () -> T): T =
        if (currentCoroutineContext()[BackgroundVideoWork.Key] == null) foreground(op) else background(op)

    private suspend fun <T> foreground(op: suspend () -> T): T {
        waitingForeground.incrementAndGet()
        try {
            runningBackground.value?.cancel()
            return heavyOpLock.withLock { op() }
        } finally {
            waitingForeground.decrementAndGet()
        }
    }

    // The lock outlasts the op even when cancelled: coroutineScope waits for it to really stop.
    private suspend fun <T> background(op: suspend () -> T): T = heavyOpLock.withLock {
        coroutineScope {
            val running = async { op() }
            runningBackground.value = running
            // After publishing, so foreground work arriving from here on can reach it.
            if (waitingForeground.value > 0) running.cancel()
            try {
                running.await()
            } catch (e: CancellationException) {
                if (isActive) throw VideoWorkYieldedException()
                throw e
            } finally {
                runningBackground.value = null
            }
        }
    }

    override suspend fun compress(
        inputPath: String,
        onProgress: VideoProgressListener?,
        trimStartMs: Long?,
        trimEndMs: Long?,
        quality: VideoQuality,
    ): String =
        heavy {
            compressor.compress(inputPath, onProgress, trimStartMs, trimEndMs, quality)
        }

    override suspend fun segment(
        inputPath: String,
        onProgress: VideoProgressListener?,
    ): SegmentedVideo? =
        heavy {
            compressor.segment(inputPath, onProgress)
        }

    override suspend fun segmentAndEncrypt(
        inputPath: String,
        keyHeader: KeyHeader,
        onProgress: VideoProgressListener?,
    ): SegmentedVideo? =
        heavy {
            compressor.segmentAndEncrypt(inputPath, keyHeader, onProgress)
        }

    override suspend fun remuxHlsToMp4(playlistPath: String, outputPath: String): Boolean =
        heavy {
            compressor.remuxHlsToMp4(playlistPath, outputPath)
        }

    override suspend fun transcode(input: ByteArray, extension: String, outputArgs: List<String>): ByteArray? =
        heavy {
            compressor.transcode(input, extension, outputArgs)
        }

    override suspend fun cacheInputVideo(fileName: String, data: ByteArray): String =
        compressor.cacheInputVideo(fileName, data)

    override suspend fun getDurationMs(inputPath: String): Long =
        probe.getDurationMs(inputPath)

    override suspend fun getFfmpegVersion(): String? =
        probe.getFfmpegVersion()

    /** Cheap metadata probe — unguarded (no heavy-op lock), mirrors [getDurationMs]. */
    override suspend fun probeVideo(inputPath: String): VideoTrackInfo? =
        probe.probeVideo(inputPath)
}
