package id.homebase.core.camera

import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

internal class UnavailableCameraEngine : CameraEngine {
    override val uiState: StateFlow<CameraUiState> =
        MutableStateFlow(CameraUiState(isAvailable = false, hasBackLens = false))
    override val errors: SharedFlow<CameraError> = MutableSharedFlow()

    override fun setLens(lens: CameraLens) = Unit
    override fun setZoomRatio(ratio: Float, animate: Boolean) = Unit
    override fun setFlash(mode: FlashMode) = Unit
    override fun setTorch(on: Boolean) = Unit
    override fun setMode(mode: CaptureMode) = Unit
    override fun setMirrorFront(enabled: Boolean) = Unit
    override fun setCaptureRotation(rotation: QuarterTurn) = Unit
    override suspend fun takePhoto(): PlatformFile? = null
    override fun startRecording(withAudio: Boolean) = Unit
    override suspend fun stopRecording(): PlatformFile? = null
    override fun release() = Unit
}
