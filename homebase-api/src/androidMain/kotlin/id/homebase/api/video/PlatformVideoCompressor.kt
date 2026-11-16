package id.homebase.api.video

import co.touchlab.kermit.Logger

internal actual fun platformVideoCompressor(): VideoCompressor = AndroidVideoCompressor

/** Media3 compresses; segmenting and the rest stay on ffmpeg for now. */
internal object AndroidVideoCompressor : VideoCompressor by FFmpegVideoCompressor {
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
            // Until ffmpeg leaves Android: a Media3 failure is logged loudly, and ffmpeg still delivers,
            // at the cost of a second pass over the source.
            Logger.e(tag = "AndroidVideoCompressor", throwable = e) { "Media3 compress failed; using ffmpeg" }
            return FFmpegVideoCompressor.compress(inputPath, onProgress, trimStartMs, trimEndMs, quality, keepMetadata)
        }
        onProgress?.invoke(1f)
        return output
    }
}
