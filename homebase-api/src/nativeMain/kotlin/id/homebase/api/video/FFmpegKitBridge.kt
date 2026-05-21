package id.homebase.api.video

/**
 * Bridge interface for FFmpegKit operations. Implemented by Swift in the iOS app and injected at
 * startup.
 */
interface FFmpegKitBridge {
    /**
     * Execute an FFmpeg command.
     * @param command The FFmpeg command string (without "ffmpeg" prefix)
     * @return Result containing success status and optional error message
     */
    fun executeFFmpeg(command: String): FFmpegResult

    /**
     * Asynchronous execution with progress reporting. [onProgress] fires
     * whenever ffmpeg-kit emits a statistics callback (~1-2 Hz) with the
     * elapsed output media time in milliseconds. [onComplete] fires exactly
     * once when ffmpeg exits. Both callbacks may be invoked on ffmpeg-kit's
     * internal worker thread — implementations downstream must be
     * thread-safe (EventBus.tryEmit on MutableSharedFlow is).
     */
    fun executeFFmpegAsync(
        command: String,
        onProgress: (timeMs: Long) -> Unit,
        onComplete: (FFmpegResult) -> Unit,
    )

    /**
     * Cancel all in-flight ffmpeg sessions. Invoked from coroutine
     * cancellation to kill the work when the caller navigates away mid-job.
     * Bridge-wide cancel — fine for the current single-job flow, but if
     * concurrent attachments land, swap to per-session cancellation by
     * returning a session id from [executeFFmpegAsync].
     */
    fun cancelAllFFmpegSessions()

    /**
     * Get media information for a file.
     * @param filePath Path to the media file
     * @return MediaInfo or null if failed
     */
    fun getMediaInformation(filePath: String): MediaInfo?

    /**
     * Returns the raw output of `ffmpeg -version` (banner + build flags), or null if it
     * could not be obtained. Callers extract the version token via parseFfmpegVersionBanner.
     */
    fun getFfmpegVersionBanner(): String?
}

/** Result of an FFmpeg/FFprobe operation. */
data class FFmpegResult(val isSuccess: Boolean, val failStackTrace: String?)

/** Media information from FFprobe. */
data class MediaInfo(val streams: List<StreamInfo>)

/** Stream information within a media file. */
data class StreamInfo(
    val type: String?,
    val tags: Map<String, String>?,
    val rotation: Int?,
    val codec: String? = null,
    val bitrate: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
)
