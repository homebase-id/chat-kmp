package id.homebase.api.video

import id.homebase.api.client.KeyHeader

expect object FFmpegUtils {
    fun getUniqueId(filePath: String): String

    suspend fun grabThumbnail(inputPath: String): String?

    suspend fun getRotationFromFile(filePath: String): Int

    suspend fun compressVideo(inputPath: String, onProgress: ((Float) -> Unit)? = null): String?

    suspend fun getDurationMs(inputPath: String): Long

    suspend fun segmentAndEncryptVideo(
        inputPath: String,
        keyHeader: KeyHeader,
        onProgress: ((Float) -> Unit)?
    ): Pair<String, String>?

    suspend fun segmentVideo(
        inputPath: String,
        onProgress: ((Float) -> Unit)?
    ): Pair<String, String>?

    suspend fun cacheInputVideo(fileName: String, data: ByteArray): String

    /**
     * Remuxes an HLS playlist (expecting unencrypted local .ts segments) into an MP4 container
     * using stream copy (no re-encoding). Returns true on success.
     */
    suspend fun remuxHlsToMp4(playlistPath: String, outputPath: String): Boolean
}
