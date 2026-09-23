package id.homebase.core.camera

import androidx.compose.ui.geometry.Offset

data class CameraUiState(
    val isBound: Boolean = false,
    val isAvailable: Boolean = true,
    val lens: CameraLens = CameraLens.Back,
    val hasBackLens: Boolean = true,
    val hasFrontLens: Boolean = false,
    val hasFlashUnit: Boolean = false,
    val zoomRatio: Float = 1f,
    val minZoom: Float = 1f,
    val maxZoom: Float = 1f,
    val lensSwitchRatios: List<Float> = emptyList(),
    val flashMode: FlashMode = FlashMode.Off,
    val torchOn: Boolean = false,
    val mode: CaptureMode = CaptureMode.Photo,
    val mirrorFront: Boolean = true,
    val supportsSimultaneousVideo: Boolean = false,
    val isRecording: Boolean = false,
    val recordingStartedAtMs: Long? = null,
    val focusPoint: Offset? = null,
)
