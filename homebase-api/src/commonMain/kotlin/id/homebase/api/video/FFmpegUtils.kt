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
    /** Luma bit depth, or null when the probe couldn't determine it. Null must be treated as
     *  "possibly 10-bit" (fail closed) — see [FfmpegCompressPlanner.isAlreadyOptimal] (#959). */
    val bitDepth: Int?,
    /** HDR flag, or null when the probe couldn't determine it (fail closed, as with [bitDepth]). */
    val isHdr: Boolean?,
)

/**
 * Low-level video operations on ffmpeg (FFmpegKit on Android and iOS, a subprocess on desktop,
 * ffmpeg.wasm on web). Input and output paths are filesystem paths; on web they are paths in the
 * in-memory okio file system. Every suspend function runs its work off the caller's thread and
 * is cancellable, except on web, where an ffmpeg.wasm call that has started cannot be
 * interrupted. Do not run two ffmpeg operations at once: Android and iOS share one engine and
 * web one worker, and overlapping sessions crash or collide.
 *
 * Failure: functions returning nullable or Boolean report failure that way and do not throw;
 * [compressVideo] throws [VideoCompressionFailedException]. A missing input is a failure.
 */
@LowLevelFfmpegApi
expect object FFmpegUtils {
    /**
     * Stable id for the file at [filePath], used to name scratch output. Same file name and size
     * give the same id; different files that share both collide. Opaque, so don't parse it.
     * Android and desktop return a UUID, iOS a decimal hash. Web returns [filePath] itself.
     * A missing file does not throw: its size counts as 0. On Android a `content://` URI also
     * has size 0, so gallery picks of the same tail collide.
     */
    fun getUniqueId(filePath: String): String

    /**
     * Rotation in degrees the container asks players to apply (0, 90, 180 or 270),
     * or 0 when there is none or it cannot be read, including a missing file.
     */
    suspend fun getRotationFromFile(filePath: String): Int

    /**
     * Probe the first video track of [inputPath] for real codec/resolution/
     * bit-depth/HDR. Returns null if the file can't be probed.
     */
    suspend fun probeVideo(inputPath: String): VideoTrackInfo?

    /**
     * Re-encodes [inputPath] and returns the output path. Never returns the input untouched:
     * there is no "already fine, skip it" outcome, because that would ship whatever the source
     * was. A failure throws [VideoCompressionFailedException].
     */
    suspend fun compressVideo(
        inputPath: String,
        onProgress: ((Float) -> Unit)? = null,
        trimStartMs: Long? = null,
        trimEndMs: Long? = null,
        quality: VideoQuality = VideoQuality.STANDARD,
    ): String

    /** Duration in milliseconds, or 0 when it cannot be determined, including a missing file. */
    suspend fun getDurationMs(inputPath: String): Long

    /**
     * Splits [inputPath] into a single-file HLS stream encrypted with AES-128 using [keyHeader]'s
     * key and IV (both must be 16 bytes), and returns (playlist path, segment path) in a new
     * scratch directory, or null on any failure. Portrait sources are re-encoded to bake in
     * rotation; others are stream-copied. [onProgress] reports 0..1. The key material is
     * deleted from disk when the call ends, success or not.
     */
    suspend fun segmentAndEncryptVideo(
        inputPath: String,
        keyHeader: KeyHeader,
        onProgress: ((Float) -> Unit)?
    ): Pair<String, String>?

    /** As [segmentAndEncryptVideo] but the segment is not encrypted. */
    suspend fun segmentVideo(
        inputPath: String,
        onProgress: ((Float) -> Unit)?
    ): Pair<String, String>?

    /** Writes [data] to a new file in the media scratch directory and returns its path. */
    suspend fun cacheInputVideo(fileName: String, data: ByteArray): String

    /**
     * Remuxes an HLS playlist (expecting unencrypted local .ts segments) into an MP4 container
     * using stream copy (no re-encoding). Returns true on success.
     */
    suspend fun remuxHlsToMp4(playlistPath: String, outputPath: String): Boolean

    /** Runs `ffmpeg -y -i <input> <outputArgs> <output>`, both files named `*.<extension>`. Null on failure. */
    suspend fun transcode(input: ByteArray, extension: String, outputArgs: List<String>): ByteArray?

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
    val raw = afterTag.substringBefore(' ').takeIf { it.isNotBlank() } ?: return null
    return normalizeFfmpegVersion(raw)
}

/**
 * Trims distributor/build noise from a raw ffmpeg version token so the About
 * screen reads consistently across desktop platforms — gyan.dev's
 * "-essentials_build-www.gyan.dev", martin-riedl's "-https://www.martin-riedl.de"
 * and johnvansickle's "-static" all surface a clean token, while release
 * strings ("n6.0", "6.1.1") and bare git-describe forms ("N-122320-g…") pass
 * through untouched (#1035).
 */
internal fun normalizeFfmpegVersion(raw: String): String {
    var v = raw
    for (marker in listOf("-essentials_build", "-full_build", "-www.", "-https://", "-http://")) {
        val i = v.indexOf(marker)
        if (i > 0) v = v.substring(0, i)
    }
    return v.removeSuffix("-static")
}
