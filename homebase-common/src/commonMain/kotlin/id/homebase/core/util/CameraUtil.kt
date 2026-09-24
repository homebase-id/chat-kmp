package id.homebase.core.util

import androidx.compose.runtime.Composable
import id.homebase.core.camera.CameraModes
import io.github.vinceglb.filekit.PlatformFile

// Emits the camera dialog while open, so call it unconditionally, never inside an `if`.
@Composable
expect fun rememberCameraManager(
    modes: CameraModes = CameraModes.Photo,
    onOpenGallery: (() -> Unit)? = null,
    onResult: (PlatformFile?) -> Unit,
): PlatformCameraManager

interface PlatformCameraManager {
    fun launch()
}
