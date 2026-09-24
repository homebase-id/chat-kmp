package id.homebase.core.camera

import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

internal class FakeCameraEngine(initial: CameraUiState = CameraUiState(isBound = true, hasFrontLens = true, hasFlashUnit = true)) :
    CameraEngine {
    override val uiState = MutableStateFlow(initial)
    override val errors = MutableSharedFlow<CameraError>(extraBufferCapacity = 4)

    var photoResult: PlatformFile? = null
    var recordingResult: PlatformFile? = null
    var reportsRecordingStart = true
    val calls = mutableListOf<String>()

    override fun setLens(lens: CameraLens) {
        calls += "lens:$lens"
        uiState.update { it.copy(lens = lens, hasFlashUnit = lens == CameraLens.Back, focusPoint = null, focusLocked = false) }
    }

    override fun setZoomRatio(ratio: Float, animate: Boolean) {
        calls += "zoom:$ratio"
        uiState.update { it.copy(zoomRatio = ratio) }
    }

    override fun setFlash(mode: FlashMode) {
        calls += "flash:$mode"
        uiState.update { it.copy(flashMode = mode) }
    }

    override fun setTorch(on: Boolean) {
        calls += "torch:$on"
        uiState.update { it.copy(torchOn = on) }
    }

    override fun setMode(mode: CaptureMode) {
        if (uiState.value.isRecording) return
        calls += "mode:$mode"
        uiState.update { it.copy(mode = mode) }
    }

    override fun setMirrorFront(enabled: Boolean) {
        uiState.update { it.copy(mirrorFront = enabled) }
    }

    override fun setCaptureRotation(rotation: QuarterTurn) {
        calls += "rotation:$rotation"
    }

    override fun setExposureBias(bias: Float) {
        calls += "exposure:$bias"
        uiState.update { it.copy(exposureBias = bias.coerceIn(-1f, 1f)) }
    }

    override suspend fun takePhoto(): PlatformFile? {
        calls += "photo"
        return photoResult
    }

    override fun startRecording(withAudio: Boolean) {
        calls += "record:audio=$withAudio"
        if (reportsRecordingStart) uiState.update { it.copy(isRecording = true, recordingStartedAtMs = 0L) }
    }

    override suspend fun stopRecording(): PlatformFile? {
        calls += "stop"
        uiState.update { it.copy(isRecording = false, recordingStartedAtMs = null) }
        return recordingResult
    }

    override fun release() = Unit
}
