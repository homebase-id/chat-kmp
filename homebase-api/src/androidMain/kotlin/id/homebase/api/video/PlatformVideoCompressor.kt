package id.homebase.api.video

import co.touchlab.kermit.Logger
import id.homebase.api.ActivityProvider
import id.homebase.api.client.KeyHeader
import id.homebase.api.file.AndroidFileOperationsProvider
import id.homebase.api.file.withResolvedFile
import id.homebase.api.video.hls.HlsSegmenter
import java.io.File
import kotlinx.coroutines.CancellationException

internal actual fun platformVideoCompressor(): VideoCompressor = AndroidVideoCompressor

/** Media3 compresses and our segmenter cuts HLS; the rest stays on ffmpeg for now. */
internal object AndroidVideoCompressor : VideoCompressor by FFmpegVideoCompressor {
    private const val TAG = "AndroidVideoCompressor"

    private val fileOps by lazy { AndroidFileOperationsProvider(ActivityProvider.requireApplicationContext()) }

    override fun readsInPlace(path: String): Boolean = fileOps.isContentUri(path)

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
            if (!fileOps.sourceExists(inputPath)) throw e
            // Until ffmpeg leaves Android: a Media3 failure is logged loudly, and ffmpeg still delivers,
            // at the cost of a second pass over the source, and of a cache copy of a content:// pick.
            Logger.e(tag = TAG, throwable = e) { "Media3 compress failed; using ffmpeg" }
            return fileOps.withResolvedFile(inputPath) { FFmpegVideoCompressor.compress(it, onProgress, trimStartMs, trimEndMs, quality, keepMetadata) }
        }
        onProgress?.invoke(1f)
        return output
    }

    override suspend fun segmentAndEncrypt(
        inputPath: String,
        keyHeader: KeyHeader,
        onProgress: VideoProgressListener?,
    ): SegmentedVideo? {
        val segmented = try {
            HlsSegmenter.segmentAndEncrypt(fileOps, inputPath, keyHeader, onProgress)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e(tag = TAG, throwable = e) { "HLS segmenting failed; using ffmpeg" }
            null
        }
        if (segmented != null) {
            onProgress?.invoke(1f)
            return segmented
        }
        // A rotated video (from the ffmpeg fallback), or one that isn't H.264 and AAC: ffmpeg turns and cuts it.
        return FFmpegVideoCompressor.segmentAndEncrypt(inputPath, keyHeader, onProgress)
    }
}
