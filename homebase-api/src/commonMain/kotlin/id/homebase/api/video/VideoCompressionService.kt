package id.homebase.api.video

import id.homebase.api.client.KeyHeader
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
 * - **Android / JVM:** parallel transcodes just thrash CPU/memory for no throughput gain.
 *
 * Rather than rely on every caller (the full-screen attachment editor, multi-video send,
 * Moments, Chat) to remember to serialize, this service guards the heavy ops — [compress],
 * [segment], [segmentAndEncrypt], [remuxHlsToMp4] — behind a single [Mutex]: a second call
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

    override suspend fun compress(
        inputPath: String,
        onProgress: VideoProgressListener?,
        trimStartMs: Long?,
        trimEndMs: Long?,
        quality: VideoQuality,
    ): String =
        heavyOpLock.withLock {
            compressor.compress(inputPath, onProgress, trimStartMs, trimEndMs, quality)
        }

    override suspend fun segment(
        inputPath: String,
        onProgress: VideoProgressListener?,
    ): SegmentedVideo? =
        heavyOpLock.withLock {
            compressor.segment(inputPath, onProgress)
        }

    override suspend fun segmentAndEncrypt(
        inputPath: String,
        keyHeader: KeyHeader,
        onProgress: VideoProgressListener?,
    ): SegmentedVideo? =
        heavyOpLock.withLock {
            compressor.segmentAndEncrypt(inputPath, keyHeader, onProgress)
        }

    override suspend fun remuxHlsToMp4(playlistPath: String, outputPath: String): Boolean =
        heavyOpLock.withLock {
            compressor.remuxHlsToMp4(playlistPath, outputPath)
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
