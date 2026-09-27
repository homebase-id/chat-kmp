package id.homebase.core.util

import androidx.compose.runtime.Composable
import id.homebase.core.camera.CameraModes
import id.homebase.core.camera.rememberInAppCameraManager
import io.github.vinceglb.filekit.PlatformFile

// Emits the camera dialog while open, so call it unconditionally, never inside an `if`.
// With awaitResultShown a capture stays on screen until the receiver calls CaptureHandoff.contentShown.
@Composable
fun rememberCameraManager(
    modes: CameraModes = CameraModes.Photo,
    onOpenGallery: (() -> Unit)? = null,
    awaitResultShown: Boolean = false,
    onResult: (PlatformFile?) -> Unit,
): PlatformCameraManager = if (isMobile()) {
    rememberInAppCameraManager(
        allowedModes = modes,
        awaitResultShown = awaitResultShown,
        onOpenGallery = onOpenGallery,
        onResult = onResult,
    )
} else {
    NoCameraManager
}

interface PlatformCameraManager {
    fun launch()
}

private object NoCameraManager : PlatformCameraManager {
    override fun launch() = Unit
}
