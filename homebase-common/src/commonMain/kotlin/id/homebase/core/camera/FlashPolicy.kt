package id.homebase.core.camera

enum class FlashControl { Hidden, Flash, Torch }

/** The requested flash mode and torch switch survive a lens flip; only what reaches the hardware is gated. */
object FlashPolicy {
    fun next(mode: FlashMode): FlashMode = when (mode) {
        FlashMode.Off -> FlashMode.Auto
        FlashMode.Auto -> FlashMode.On
        FlashMode.On -> FlashMode.Off
    }

    fun control(mode: CaptureMode, hasFlashUnit: Boolean): FlashControl = when {
        !hasFlashUnit -> FlashControl.Hidden
        mode == CaptureMode.Video -> FlashControl.Torch
        else -> FlashControl.Flash
    }

    fun effectivePhotoFlash(requested: FlashMode, hasFlashUnit: Boolean): FlashMode =
        if (hasFlashUnit) requested else FlashMode.Off

    fun effectiveTorch(requested: Boolean, mode: CaptureMode, hasFlashUnit: Boolean): Boolean =
        requested && hasFlashUnit && mode == CaptureMode.Video
}
