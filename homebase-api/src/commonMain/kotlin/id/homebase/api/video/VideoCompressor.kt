package id.homebase.api.video

import id.homebase.api.client.KeyHeader

/**
 * The encode/segment side of the video pipeline, behind an interface so it can be
 * replaced with a fake in `commonTest` and so a future non-ffmpeg backend can be
 * slotted in without touching callers.
 *
 * Callers reach it through the [VideoCompressionService] singleton, the counterpart
 * to [VideoThumbnailService] on the thumbnail side. Each platform picks its
 * implementation ([platformVideoCompressor]): Media3 on Android, ffmpeg elsewhere.
 *
 * Paths are platform-specific `String`s for the same reason the thumbnail seam
 * uses them — see the KDoc on [VideoThumbnailService].
 */
interface VideoCompressor {
    /**
     * Re-encodes [inputPath] to the [quality] envelope, optionally trimming to
     * `[trimStartMs, trimEndMs]`. Returns the path of the re-encoded output — always a new
     * file, never the input. A failure throws [VideoCompressionFailedException]; there is no
     * fall-back-to-input, which would defeat the 8-bit pin. [keepMetadata] is the app's policy:
     * the source's location and capture time are copied, or dropped.
     */
    suspend fun compress(
        inputPath: String,
        onProgress: VideoProgressListener? = null,
        trimStartMs: Long? = null,
        trimEndMs: Long? = null,
        quality: VideoQuality = VideoQuality.STANDARD,
        keepMetadata: Boolean = false,
    ): String

    /**
     * Whether [path] can be processed where it is, with no copy into the cache first: by [compress], and by the
     * platform's poster decoder and file size, which get the same path. False means the caller resolves it to a
     * file first (`FileOperationsProvider.resolveToFilePath`).
     */
    fun readsInPlace(path: String): Boolean = false

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
