@file:OptIn(LowLevelFfmpegApi::class) // thin adapter over the FFmpegUtils backend by design

package id.homebase.api.video

import id.homebase.api.client.KeyHeader

/**
 * ffmpeg-backed [VideoCompressor]. A thin `commonMain` adapter over the
 * [FFmpegUtils] `expect object` — same delegation pattern the thumbnail seam uses
 * (e.g. [FFmpegSubprocessVideoDecoder] over `FFmpegUtils.grabThumbnail`).
 * `FFmpegUtils` still owns the per-platform dispatch, so no `expect`/`actual` is
 * needed here.
 *
 * **Progress contract — clamped 0f..1f, terminal 1f.** Mid-run progress from the
 * platform actuals is best-effort, timing-dependent, AND not uniformly bounded: the
 * JVM `-progress` pipe and FFmpegKit statistics path `coerceIn(0f, 1f)` themselves,
 * but ffmpeg.wasm's native progress event reports raw `out_time/duration`, which
 * overshoots slightly past `1.0` when the encoder runs a hair past the trim window
 * (observed `1.03` on a trimmed clip). [VideoProgressListener] documents a `0f..1f`
 * fraction, so this seam clamps every forwarded value — making the contract true on
 * every backend rather than relying on each actual to do it.
 *
 * On a *successful* op this also emits a final `onProgress(1f)`: FFmpegKit's
 * statistics callback can deliver *zero* mid-run ticks for a sub-second encode, so a
 * guaranteed terminal `1f` gives every platform the same "completed op ends at 100%"
 * contract the upload UI relies on and makes the wiring deterministically testable in
 * `FFmpegCompressionCommonTest`. Nothing is emitted when the op returns null
 * (skipped / failed — no work happened).
 *
 * Stateless, hence an `object`.
 */
internal object FFmpegVideoCompressor : VideoCompressor {

    /** Forwards progress to [this], coercing each fraction into the documented 0f..1f. */
    private fun VideoProgressListener.clampedToUnit(): VideoProgressListener =
        { fraction -> this(fraction.coerceIn(0f, 1f)) }

    override suspend fun compress(
        inputPath: String,
        onProgress: VideoProgressListener?,
        trimStartMs: Long?,
        trimEndMs: Long?,
        quality: VideoQuality,
        allowTenBit: Boolean,
    ): String? =
        FFmpegUtils.compressVideo(
            inputPath = inputPath,
            onProgress = onProgress?.clampedToUnit(),
            trimStartMs = trimStartMs,
            trimEndMs = trimEndMs,
            quality = quality,
            allowTenBit = allowTenBit,
        )?.also { onProgress?.invoke(1f) }

    override suspend fun segment(
        inputPath: String,
        onProgress: VideoProgressListener?,
    ): SegmentedVideo? =
        FFmpegUtils.segmentVideo(inputPath, onProgress?.clampedToUnit())
            ?.let { (playlist, segments) -> SegmentedVideo(playlist, segments) }
            ?.also { onProgress?.invoke(1f) }

    override suspend fun segmentAndEncrypt(
        inputPath: String,
        keyHeader: KeyHeader,
        onProgress: VideoProgressListener?,
    ): SegmentedVideo? =
        FFmpegUtils.segmentAndEncryptVideo(inputPath, keyHeader, onProgress?.clampedToUnit())
            ?.let { (playlist, segments) -> SegmentedVideo(playlist, segments) }
            ?.also { onProgress?.invoke(1f) }

    override suspend fun remuxHlsToMp4(playlistPath: String, outputPath: String): Boolean =
        FFmpegUtils.remuxHlsToMp4(playlistPath, outputPath)

    override suspend fun cacheInputVideo(fileName: String, data: ByteArray): String =
        FFmpegUtils.cacheInputVideo(fileName, data)
}
