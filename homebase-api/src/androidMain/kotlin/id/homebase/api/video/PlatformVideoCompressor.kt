package id.homebase.api.video

import co.touchlab.kermit.Logger
import id.homebase.api.client.KeyHeader
import id.homebase.api.video.hls.HlsSegmenter

internal actual fun platformVideoCompressor(): VideoCompressor = AndroidVideoCompressor

/** Media3 compresses and our segmenter cuts HLS. A failure fails the send: there is no second engine. */
internal object AndroidVideoCompressor : VideoCompressor {
    private const val TAG = "AndroidVideoCompressor"

    private val fileOps get() = Media3VideoCompressor.fileOps

    // The media library's URIs only: the app's own media permission covers them for as long as it lasts. A
    // picker's grant can end with its screen, before a queued compress reads the file, so that pick is copied.
    override fun readsInPlace(path: String): Boolean = path.startsWith("content://media/")

    override suspend fun compress(
        inputPath: String,
        onProgress: VideoProgressListener?,
        trimStartMs: Long?,
        trimEndMs: Long?,
        quality: VideoQuality,
        keepMetadata: Boolean,
    ): String {
        val output = try {
            Media3VideoCompressor.compress(inputPath, onProgress, trimStartMs, trimEndMs, quality, keepMetadata)
        } catch (e: VideoCompressionFailedException) {
            Logger.e(tag = TAG, throwable = e) { "Media3 compress failed: ${e.message}" }
            throw e
        }
        onProgress?.invoke(1f)
        return output
    }

    override suspend fun segmentAndEncrypt(
        inputPath: String,
        keyHeader: KeyHeader,
        onProgress: VideoProgressListener?,
    ): SegmentedVideo? = HlsSegmenter.segmentForUpload(fileOps, inputPath, keyHeader, onProgress)

    override suspend fun segment(inputPath: String, onProgress: VideoProgressListener?): SegmentedVideo? =
        HlsSegmenter.segmentForUpload(fileOps, inputPath, null, onProgress)
}
