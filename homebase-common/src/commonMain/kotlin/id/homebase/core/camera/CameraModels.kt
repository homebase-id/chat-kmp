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
