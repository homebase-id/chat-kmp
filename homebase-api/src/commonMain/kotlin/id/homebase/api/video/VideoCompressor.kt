package id.homebase.api.video

import id.homebase.api.client.KeyHeader

/**
 * The encode/segment side of the video pipeline, behind an interface so it can be
 * replaced with a fake in `commonTest` and so a future non-ffmpeg backend can be
 * slotted in without touching callers.
 *
 * The production implementation is [FFmpegVideoCompressor], reached through the
 * [VideoCompressionService] singleton — the symmetric counterpart to
 * [VideoThumbnailService] on the thumbnail side. Both delegate to the low-level
 * [FFmpegUtils] `expect object`, which still owns the per-platform ffmpeg
 * dispatch.
 *
 * Paths are platform-specific `String`s for the same reason the thumbnail seam
 * uses them — see the KDoc on [VideoThumbnailService].
 */
interface VideoCompressor {
    /**
     * Re-encodes [inputPath] to the [quality] envelope, optionally trimming to
     * `[trimStartMs, trimEndMs]`. Returns the path of the re-encoded output — always a new
     * file, never the input. A failure throws [VideoCompressionFailedException]; there is no
     * fall-back-to-input, which would defeat the 8-bit pin.
     */
    suspend fun compress(
        inputPath: String,
        onProgress: VideoProgressListener? = null,
        trimStartMs: Long? = null,
        trimEndMs: Long? = null,
        quality: VideoQuality = VideoQuality.STANDARD,
    ): String

    /** Segments [inputPath] into an unencrypted HLS playlist + `.ts` segments. */
    suspend fun segment(
        inputPath: String,
        onProgress: VideoProgressListener?,
    ): SegmentedVideo?

    /**
     * Segments [inputPath] into an HLS playlist whose `.ts` segments are AES
     * encrypted with [keyHeader]. Returns null on failure.
     */
    suspend fun segmentAndEncrypt(
        inputPath: String,
        keyHeader: KeyHeader,
        onProgress: VideoProgressListener?,
    ): SegmentedVideo?

    /**
     * Remuxes an HLS playlist (expecting unencrypted local `.ts` segments) into an
     * MP4 container using stream copy (no re-encoding). Returns true on success.
     */
    suspend fun remuxHlsToMp4(playlistPath: String, outputPath: String): Boolean

    /** Materializes [data] into a cache file named [fileName], returning its path. */
    suspend fun cacheInputVideo(fileName: String, data: ByteArray): String
}
