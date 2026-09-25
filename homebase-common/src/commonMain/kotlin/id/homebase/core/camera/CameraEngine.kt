package id.homebase.core.camera

import androidx.compose.runtime.Composable
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

interface CameraEngine {
    val uiState: StateFlow<CameraUiState>
    val errors: SharedFlow<CameraError>

    fun setLens(lens: CameraLens)

    /** [ratio] is the displayed ratio (1 = main wide lens), so ultra-wide sits below 1. */
    fun setZoomRatio(ratio: Float, animate: Boolean = false)

    fun setFlash(mode: FlashMode)
    fun setTorch(on: Boolean)
    fun setMode(mode: CaptureMode)

    /** Android bakes video mirroring into the bound use case, so the preference must be known before capture. */
    fun setMirrorFront(enabled: Boolean)

    fun setCaptureRotation(rotation: QuarterTurn)

    suspend fun takePhoto(): PlatformFile?

    fun startRecording(withAudio: Boolean)

    /**
     * Stops the current recording and returns its file. A recording that already finalized on its own
     * (backgrounding, interruption, storage) is returned here too, once.
     */
    suspend fun stopRecording(): PlatformFile?

    fun release()
}

/**
 * Create only after camera permission is granted: binding without it fails rather than waiting.
 * A [warm] engine from [CameraWarmer] is adopted instead of opening a second camera, and released with the call.
 * With [recordsVideo] a granted mic is attached while the session is first configured: on iOS, adding it to a
 * running session blanks the preview.
 */
@Composable
expect fun rememberCameraEngine(recordsVideo: Boolean, warm: CameraEngine? = null): CameraEngine

/** Opens the camera before its UI composes; null when permission isn't granted yet. The caller owns what it returns. */
fun interface CameraWarmer {
    fun warm(recordsVideo: Boolean): CameraEngine?
}

@Composable
expect fun rememberCameraWarmer(): CameraWarmer
