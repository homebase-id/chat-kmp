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

/** The largest still size up to 12 MP, or the smallest one when every size is larger. */
internal fun choosePhotoSize(sizes: List<Pair<Int, Int>>): Pair<Int, Int>? {
    fun pixels(size: Pair<Int, Int>) = size.first.toLong() * size.second
    return sizes.filter { pixels(it) <= MAX_PHOTO_PIXELS }.maxByOrNull(::pixels) ?: sizes.minByOrNull(::pixels)
}
