package id.homebase.api.video

import id.homebase.api.client.KeyHeader

/**
 * The encode/segment side of the video pipeline. Callers reach it through [VideoCompressionService]; each platform
 * picks its implementation ([platformVideoCompressor]): Media3 on Android, AVFoundation on iOS, ffmpeg on desktop and web.
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
     *
     * Every platform's output: an MP4 of 8-bit SDR H.264 (HDR is tone-mapped or relabelled), with AAC audio when the
     * source has audio; frames upright with no rotation flag, since an HLS stream can't carry one; the short edge
     * scaled down to [VideoQuality.targets], never up ([computeOutputDims]). Cancelling returns once the work has
     * stopped.
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
     * Segments [inputPath] into an HLS playlist whose `.ts` segments are AES encrypted with [keyHeader], or left in
     * the clear with no key line in the playlist when it is null (an unencrypted upload). Returns null on failure.
     */
    suspend fun segmentAndEncrypt(
        inputPath: String,
        keyHeader: KeyHeader?,
        onProgress: VideoProgressListener?,
    ): SegmentedVideo?
}
