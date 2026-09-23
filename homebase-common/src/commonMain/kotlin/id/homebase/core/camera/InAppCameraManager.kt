package id.homebase.core.camera

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import id.homebase.core.util.PlatformCameraManager
import io.github.vinceglb.filekit.PlatformFile

@Stable
class InAppCameraLauncher internal constructor() : PlatformCameraManager {
    internal var openMode by mutableStateOf<CaptureMode?>(null)

    override fun launch() = launch(CaptureMode.Photo)

    fun launch(initialMode: CaptureMode) {
        openMode = initialMode
    }
}

/**
 * Emits the camera dialog while open, so call it unconditionally (not inside an `if`).
 * [onResult] gets the captured file, or null when the camera is closed without one.
 */
@Composable
fun rememberInAppCameraManager(
    allowedModes: CameraModes,
    mirrorFront: Boolean = true,
    onResult: (PlatformFile?) -> Unit,
): InAppCameraLauncher {
    val launcher = remember { InAppCameraLauncher() }
    val currentOnResult by rememberUpdatedState(onResult)
    val mode = launcher.openMode
    if (mode != null) {
        CameraCaptureDialog(
            allowedModes = allowedModes,
            initialMode = mode,
            mirrorFront = mirrorFront,
            onResult = { file ->
                launcher.openMode = null
                currentOnResult(file)
            },
            onDismiss = {
                launcher.openMode = null
                currentOnResult(null)
            },
        )
    }
    return launcher
}
