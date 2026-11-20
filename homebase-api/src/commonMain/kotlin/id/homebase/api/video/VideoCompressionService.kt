package id.homebase.api.video

import co.touchlab.kermit.Logger
import id.homebase.api.client.KeyHeader
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.video.hls.NotRemuxableException
import id.homebase.api.video.hls.TsToMp4
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
import kotlinx.coroutines.flow.Flow

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
 * Consumers see one generic shape ([VideoCompressor] + [VideoProber]). Each platform picks its own
 * ([platformVideoCompressor], [platformVideoProber]): Media3 on Android, AVFoundation on iOS, ffmpeg on
 * desktop and web.
 *
 * **Concurrency: one heavy op at a time — enforced here.** The backend is NOT safe for
 * concurrent heavy operations:
 * - **Web (ffmpeg.wasm):** a single shared ffmpeg instance with fixed MEMFS scratch
 *   names (`input.mp4`, `index.m3u8`, …) — two simultaneous `compress`/`segment*` calls
 *   clobber each other's files.
 * - **iOS (AVFoundation):** two exports at once compete for the hardware codecs. A cancelled export
 *   counts until it has stopped.
 * - **Android (Media3):** two exports at once compete for the hardware codecs. A cancelled export
 *   counts until its codecs are released.
 * - **JVM:** parallel transcodes just thrash CPU/memory for no throughput gain.
 *
 * Rather than rely on every caller (the full-screen attachment editor, multi-video send,
 * Moments, Chat) to remember to serialize, this service guards the heavy ops — [compress]
 * and [segmentAndEncrypt] — behind a single [Mutex]: a second call
 * waits until the first completes (and `withLock` is cancellation-aware, so a waiter that's
 * cancelled unblocks the queue cleanly). The cheap probes ([getDurationMs],
 * [getFfmpegVersion]) are unguarded — they're fine to call freely.
 *
 * Note: this mutex serializes heavy compress/segment ops against *each other*, not against
 * [VideoThumbnailService]'s decodes, which may still run concurrently.
 */
object VideoCompressionService : VideoCompressor, VideoProber {

    // Delegates are `var` only so tests can substitute fakes; production never reassigns them.
    internal var compressor: VideoCompressor = platformVideoCompressor()
    internal var probe: VideoProber = platformVideoProber()

    /** Serializes the heavy ops so two never run on the shared backend at once. */
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
        keepMetadata: Boolean,
    ): String =
        heavy {
            compressor.compress(inputPath, onProgress, trimStartMs, trimEndMs, quality, keepMetadata)
        }

    override fun readsInPlace(path: String): Boolean = compressor.readsInPlace(path)

    override suspend fun segmentAndEncrypt(
        inputPath: String,
        keyHeader: KeyHeader,
        onProgress: VideoProgressListener?,
    ): SegmentedVideo? =
        heavy {
            compressor.segmentAndEncrypt(inputPath, keyHeader, onProgress)
        }

    /**
     * Copies a decrypted HLS video's `.ts` into an MP4 at [outputPath], without re-encoding, the same way on every
     * platform. False, logged, when [tsPath] isn't H.264 (and AAC) in MPEG-TS; nothing is left at [outputPath] then.
     */
    suspend fun remuxTsToMp4(fileOps: FileOperationsProvider, tsPath: String, outputPath: String): Boolean =
        remuxTsToMp4(fileOps, fileOps.readFileAsFlow(tsPath, TsToMp4.CHUNK), outputPath, tsPath)

    /**
     * [remuxTsToMp4] on the TS as it arrives (a download's decrypted stream), with no `.ts` written. Whatever the stream
     * throws (a dropped download, a failed decrypt, a file over the platform's limit) is rethrown, the output deleted.
     */
    internal suspend fun remuxTsToMp4(fileOps: FileOperationsProvider, ts: Flow<ByteArray>, outputPath: String, label: String): Boolean = try {
        TsToMp4.remux(fileOps, ts, outputPath, label)
        true
    } catch (e: NotRemuxableException) {
        Logger.e(tag = "VideoCompressionService", throwable = e) { "remux of $label failed" }
        fileOps.deleteTempFile(outputPath)
        false
    } catch (e: Throwable) {
        fileOps.deleteTempFile(outputPath)
        throw e
    }

    override suspend fun getDurationMs(inputPath: String): Long =
        probe.getDurationMs(inputPath)

    override suspend fun getFfmpegVersion(): String? =
        probe.getFfmpegVersion()

    /** Cheap metadata probe — unguarded (no heavy-op lock), mirrors [getDurationMs]. */
    override suspend fun probeVideo(inputPath: String): VideoTrackInfo? =
        probe.probeVideo(inputPath)

    override fun canRead(contentType: String): Boolean = probe.canRead(contentType)
}

internal expect fun platformVideoCompressor(): VideoCompressor

internal expect fun platformVideoProber(): VideoProber
