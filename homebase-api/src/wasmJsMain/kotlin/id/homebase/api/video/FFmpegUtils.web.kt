package id.homebase.api.video

import id.homebase.api.client.KeyHeader

// Pre-flight stub. Video processing on the web will require a separate
// strategy (ffmpeg.wasm, MediaRecorder, or server-side transcoding) — out
// of scope for the pre-flight pass.
actual object FFmpegUtils {
    actual fun getUniqueId(filePath: String): String = filePath

    actual suspend fun grabThumbnail(inputPath: String): String? = null

    actual suspend fun getRotationFromFile(filePath: String): Int = 0

    actual suspend fun compressVideo(
        inputPath: String,
        onProgress: ((Float) -> Unit)?,
        trimStartMs: Long?,
        trimEndMs: Long?,
    ): String? = null

    actual suspend fun getDurationMs(inputPath: String): Long = 0L

    actual suspend fun segmentAndEncryptVideo(
        inputPath: String,
        keyHeader: KeyHeader,
        onProgress: ((Float) -> Unit)?,
    ): Pair<String, String>? = null

    actual suspend fun segmentVideo(
        inputPath: String,
        onProgress: ((Float) -> Unit)?,
    ): Pair<String, String>? = null

    actual suspend fun cacheInputVideo(fileName: String, data: ByteArray): String = fileName

    actual suspend fun remuxHlsToMp4(playlistPath: String, outputPath: String): Boolean = false
}
