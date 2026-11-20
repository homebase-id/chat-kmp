@file:OptIn(LowLevelFfmpegApi::class) // thin adapter over the FFmpegUtils backend by design

package id.homebase.api.video

import id.homebase.api.client.KeyHeader

/**
 * ffmpeg-backed [VideoCompressor] for desktop and web, over [FFmpegUtils]. Progress is clamped to 0f..1f, since
 * ffmpeg.wasm overshoots past 1.0 on a trimmed clip, and a success ends with 1f, since a sub-second encode may tick
 * none (`VideoCompressionCommonTest`).
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
        keepMetadata: Boolean,
    ): String =
        FFmpegUtils.compressVideo(
            inputPath = inputPath,
            onProgress = onProgress?.clampedToUnit(),
            trimStartMs = trimStartMs,
            trimEndMs = trimEndMs,
            quality = quality,
            keepMetadata = keepMetadata,
        ).also { onProgress?.invoke(1f) }

    override suspend fun segmentAndEncrypt(
        inputPath: String,
        keyHeader: KeyHeader,
        onProgress: VideoProgressListener?,
    ): SegmentedVideo? =
        FFmpegUtils.segmentAndEncryptVideo(inputPath, keyHeader, onProgress?.clampedToUnit())
            ?.let { (playlist, segments) -> SegmentedVideo(playlist, segments) }
            ?.also { onProgress?.invoke(1f) }

}
