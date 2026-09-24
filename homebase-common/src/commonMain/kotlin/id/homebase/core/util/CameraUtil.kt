package id.homebase.core.util

import androidx.compose.runtime.Composable
import id.homebase.core.camera.CameraModes
import id.homebase.core.camera.CaptureMode
import io.github.vinceglb.filekit.PlatformFile

/**
 * Emits the camera dialog while open on Android and iOS, so call it unconditionally (not inside
 * an `if`). [onResult] gets the capture, or null when the camera closes without one.
 */
@Composable
expect fun rememberCameraManager(
    modes: CameraModes = CameraModes.Photo,
    onResult: (PlatformFile?) -> Unit,
): PlatformCameraManager

interface PlatformCameraManager {
    fun launch(initialMode: CaptureMode = CaptureMode.Photo)
}
