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
     *
     * Returns the FFmpegKit session ID so the caller can cancel this specific
     * session via [cancelFFmpegSession] without killing unrelated concurrent
     * sessions (e.g. a thumbnail-strip extraction running alongside this
     * compression/segmentation job).
     */
    fun executeFFmpegAsync(
        command: String,
        onProgress: (timeMs: Long) -> Unit,
        onComplete: (FFmpegResult) -> Unit,
    ): Long

    /**
     * Same shape as [executeFFmpegAsync] but takes an arg list instead of a single shell-style
     * command string. Use this for any command containing user-supplied paths (or anything
     * else that might contain quotes / spaces / shell metacharacters) — FFmpegKit's
     * `executeWithArgumentsAsync` skips its argv parser entirely, so the arg list is the
     * `ProcessBuilder(List<String>)` equivalent.
     *
     * Returns the FFmpegKit session ID so the caller can cancel this specific session
     * via [cancelFFmpegSession] without killing unrelated concurrent sessions.
     */
    fun executeFFmpegAsyncArgs(
        args: List<String>,
        onProgress: (timeMs: Long) -> Unit,
        onComplete: (FFmpegResult) -> Unit,
    ): Long

    /**
     * Cancel all in-flight ffmpeg sessions. Engine-wide — it tears down EVERY
     * session, not just the caller's, so any code that may run alongside other
     * FFmpegKit work must use [cancelFFmpegSession] instead. Both the upload
     * pipeline ([executeFFmpegAsync]) and thumbnail extraction
     * ([executeFFmpegAsyncArgs]) now cancel per-session; this remains only as a
     * blunt "stop everything" escape hatch with no current production caller.
     */
    fun cancelAllFFmpegSessions()

    /**
     * Cancel a single ffmpeg session by ID. Use this when the caller's session
     * may run concurrently with other FFmpegKit work (e.g. thumbnail strip
     * extraction alongside video upload compression).
     */
    fun cancelFFmpegSession(sessionId: Long)

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
    /** ffprobe `pix_fmt` token, e.g. `yuv420p` / `yuv420p10le`. Null if unknown. */
    val pixelFormat: String? = null,
    /** ffprobe `color_transfer`, e.g. `bt709` / `smpte2084` / `arib-std-b67`. */
    val colorTransfer: String? = null,
    /** ffprobe `color_primaries`, e.g. `bt709` / `bt2020`. */
    val colorPrimaries: String? = null,
)
