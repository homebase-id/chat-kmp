package id.homebase.api.video

import id.homebase.api.client.KeyHeader

/**
 * Real technical metadata for the first video track of a file, probed via the
 * platform-native API (ffprobe / MediaExtractor / FFmpegKit). Persisted into
 * [VideoMetadata] at encode time so the inline debug overlay can show truthful
 * codec info instead of the hardcoded placeholder. Null fields / 0 dims mean
 * the probe couldn't determine that property.
 */
data class VideoTrackInfo(
    val codec: String?,
    val widthPx: Int,
    val heightPx: Int,
    val bitDepth: Int,
    val isHdr: Boolean,
)

@LowLevelFfmpegApi
expect object FFmpegUtils {
    fun getUniqueId(filePath: String): String

    suspend fun grabThumbnail(inputPath: String): String?

    suspend fun getRotationFromFile(filePath: String): Int

    /**
     * Probe the first video track of [inputPath] for real codec/resolution/
     * bit-depth/HDR. Returns null if the file can't be probed.
     */
    suspend fun probeVideo(inputPath: String): VideoTrackInfo?

    suspend fun compressVideo(
        inputPath: String,
        onProgress: ((Float) -> Unit)? = null,
        trimStartMs: Long? = null,
        trimEndMs: Long? = null,
        quality: VideoQuality = VideoQuality.STANDARD,
        allowTenBit: Boolean = false,
    ): String?

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

    /**
     * Version reported by the underlying ffmpeg (e.g. "n6.0", "6.1.1"), or null if it
     * could not be determined. Result is process-stable; actuals memoize after the first call.
     */
    suspend fun getFfmpegVersion(): String?
}

/**
 * Parses the version token out of the first line of `ffmpeg -version` output.
 * Banner format: "ffmpeg version <ver> Copyright (c) ..."
 */
internal fun parseFfmpegVersionBanner(output: String?): String? {
    if (output.isNullOrBlank()) return null
    val firstLine = output.lineSequence()
        .firstOrNull { it.contains("ffmpeg version", ignoreCase = true) }
        ?: return null
    val afterTag = firstLine.substringAfter("ffmpeg version", "").trim()
    if (afterTag.isEmpty()) return null
    return afterTag.substringBefore(' ').takeIf { it.isNotBlank() }
}
