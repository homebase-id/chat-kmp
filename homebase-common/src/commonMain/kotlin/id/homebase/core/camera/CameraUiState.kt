package id.homebase.core.camera

import androidx.compose.ui.geometry.Offset

data class CameraUiState(
    val isBound: Boolean = false,
    /** Bound, but the new camera hasn't delivered a frame yet: the preview still holds the previous lens's. */
    val awaitingFirstFrame: Boolean = false,
    val isAvailable: Boolean = true,
    val lens: CameraLens = CameraLens.Back,
    val hasBackLens: Boolean = true,
    val hasFrontLens: Boolean = false,
    /** An LED, or on the iOS front camera the screen (Retina Flash), which has no torch. */
    val hasPhotoFlash: Boolean = false,
    val hasTorch: Boolean = false,
    val zoomRatio: Float = 1f,
    val minZoom: Float = 1f,
    val maxZoom: Float = 1f,
    val lensSwitchRatios: List<Float> = emptyList(),
    val flashMode: FlashMode = FlashMode.Off,
    val torchOn: Boolean = false,
    val mode: CaptureMode = CaptureMode.Photo,
    val mirrorFront: Boolean = true,
    val supportsSimultaneousVideo: Boolean = false,
    /** Portrait width/height of a letterboxed preview; null when the preview fills the screen. */
    val previewAspectRatio: Float? = null,
    val isRecording: Boolean = false,
    val recordingStartedAtMs: Long? = null,
    val focusPoint: Offset? = null,
) {
    fun hasLens(lens: CameraLens): Boolean = if (lens == CameraLens.Front) hasFrontLens else hasBackLens

    fun clampZoom(ratio: Float): Float = ratio.coerceIn(minZoom, maxOf(minZoom, maxZoom))
}

val CameraUiState.mirrorsCapture: Boolean get() = lens == CameraLens.Front && mirrorFront
