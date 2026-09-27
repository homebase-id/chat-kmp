package id.homebase.core.camera

enum class FlashControl { Hidden, Flash, Torch }

/** The requested flash mode and torch switch survive a lens flip; only what reaches the hardware is gated. */
object FlashPolicy {
    fun next(mode: FlashMode): FlashMode = when (mode) {
        FlashMode.Off -> FlashMode.Auto
        FlashMode.Auto -> FlashMode.On
        FlashMode.On -> FlashMode.Off
    }

    // A lens can flash a still without having a torch (iOS front Retina Flash), so each mode checks its own light.
    fun control(mode: CaptureMode, hasPhotoFlash: Boolean, hasTorch: Boolean): FlashControl = when {
        mode == CaptureMode.Video -> if (hasTorch) FlashControl.Torch else FlashControl.Hidden
        hasPhotoFlash -> FlashControl.Flash
        else -> FlashControl.Hidden
    }

    fun effectivePhotoFlash(requested: FlashMode, hasPhotoFlash: Boolean): FlashMode =
        if (hasPhotoFlash) requested else FlashMode.Off

    fun effectiveTorch(requested: Boolean, mode: CaptureMode, hasTorch: Boolean): Boolean =
        requested && hasTorch && mode == CaptureMode.Video
}
