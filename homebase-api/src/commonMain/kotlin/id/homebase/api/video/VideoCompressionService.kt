package id.homebase.api.video

import id.homebase.api.client.KeyHeader

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
 * **Concurrency: run one compress/segment at a time.** Despite being a freely-callable
 * singleton, the backend is NOT safe for concurrent heavy operations:
 * - **Web (ffmpeg.wasm):** a single shared ffmpeg instance with fixed MEMFS scratch
 *   names (`input.mp4`, `index.m3u8`, …) — two simultaneous `compress`/`segment*` calls
 *   clobber each other's files.
 * - **iOS (FFmpegKit):** cancellation is engine-wide (`cancelAllFFmpegSessions`), so a
 *   cancel of one job tears down any other in flight.
 * - **Android / JVM:** parallel transcodes just thrash CPU/memory for no throughput gain.
 *
 * Callers that process several videos (e.g. the full-screen attachment editor, multi-video
 * send) must therefore **serialize** these calls — queue them through one coordinator rather
 * than launching them concurrently. (`getDurationMs` / `getFfmpegVersion` probes are cheap and
 * fine to call freely; this restriction is about the encode/segment work.)
 */
object VideoCompressionService : VideoCompressor, VideoProber {

    private val compressor: VideoCompressor = FFmpegVideoCompressor
    private val probe: VideoProber = FFmpegVideoProber

    override suspend fun compress(
        inputPath: String,
        onProgress: VideoProgressListener?,
        trimStartMs: Long?,
        trimEndMs: Long?,
        quality: VideoQuality,
    ): String? =
        compressor.compress(inputPath, onProgress, trimStartMs, trimEndMs, quality)

    override suspend fun segment(
        inputPath: String,
        onProgress: VideoProgressListener?,
    ): SegmentedVideo? =
        compressor.segment(inputPath, onProgress)

    override suspend fun segmentAndEncrypt(
        inputPath: String,
        keyHeader: KeyHeader,
        onProgress: VideoProgressListener?,
    ): SegmentedVideo? =
        compressor.segmentAndEncrypt(inputPath, keyHeader, onProgress)

    override suspend fun remuxHlsToMp4(playlistPath: String, outputPath: String): Boolean =
        compressor.remuxHlsToMp4(playlistPath, outputPath)

    override suspend fun cacheInputVideo(fileName: String, data: ByteArray): String =
        compressor.cacheInputVideo(fileName, data)

    override suspend fun getDurationMs(inputPath: String): Long =
        probe.getDurationMs(inputPath)

    override suspend fun getFfmpegVersion(): String? =
        probe.getFfmpegVersion()
}
