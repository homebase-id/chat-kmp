package id.homebase.core.camera

enum class CameraLens { Back, Front }

enum class FlashMode { Off, Auto, On }

enum class CaptureMode { Photo, Video }

enum class CameraModes(val allowed: Set<CaptureMode>) {
    Photo(setOf(CaptureMode.Photo)),
    PhotoAndVideo(setOf(CaptureMode.Photo, CaptureMode.Video));

    fun allows(mode: CaptureMode): Boolean = mode in allowed
}

/** Physical device rotation, clockwise from the natural (portrait) posture. */
enum class QuarterTurn(val degrees: Int) {
    R0(0),
    R90(90),
    R180(180),
    R270(270);

    /** Rotation that keeps an on-screen icon upright while the UI itself stays portrait. */
    val uprightIconDegrees: Float get() = (-degrees).toFloat()
}

sealed interface CameraError {
    data object NoCamera : CameraError
    data object BindFailed : CameraError
    data object CameraInUse : CameraError
    data object Interrupted : CameraError
    data object InsufficientStorage : CameraError
    data class PhotoFailed(val message: String?) : CameraError
    data class RecordingFailed(val message: String?) : CameraError
}

/** When a recording's first sample was written, from the recorder's running duration; null until one has been. */
internal fun recordingStartedAtMs(nowMs: Long, recordedDurationNanos: Long): Long? =
    if (recordedDurationNanos <= 0L) null else nowMs - recordedDurationNanos / 1_000_000

// A chat photo gains nothing past 12 MP, and 24/48 MP stills take visibly longer to capture and send.
private const val MAX_PHOTO_PIXELS = 4032L * 3024L

private val Pair<Int, Int>.pixels: Long get() = first.toLong() * second

/** The largest still size up to 12 MP, or the smallest one when every size is larger. */
internal fun choosePhotoSize(sizes: List<Pair<Int, Int>>): Pair<Int, Int>? {
    return sizes.filter { it.pixels <= MAX_PHOTO_PIXELS }.maxByOrNull { it.pixels } ?: sizes.minByOrNull { it.pixels }
}

internal data class VideoFormat(val width: Int, val height: Int, val maxFps: Double, val photoSizes: List<Pair<Int, Int>>)

/**
 * Index of the 4:3 format for Photo mode: one that can still record (30 fps, 1080–1440 tall) so a hold-to-record
 * needs no reconfigure, taking the largest still up to 12 MP and then the largest video.
 */
internal fun choosePhotoModeFormat(formats: List<VideoFormat>): Int? {
    fun stillScore(format: VideoFormat) =
        choosePhotoSize(format.photoSizes)?.pixels?.takeIf { it <= MAX_PHOTO_PIXELS } ?: 0L
    return formats.withIndex()
        .filter { (_, f) ->
            val long = maxOf(f.width, f.height)
            val short = minOf(f.width, f.height)
            long * 3 == short * 4 && short in 1080..1440 && f.maxFps >= 30.0 && f.photoSizes.isNotEmpty()
        }
        .maxWithOrNull(compareBy({ stillScore(it.value) }, { (it.value.width to it.value.height).pixels }))
        ?.index
}
