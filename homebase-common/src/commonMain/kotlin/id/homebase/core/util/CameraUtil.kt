package id.homebase.core.util

import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Rect
import id.homebase.core.camera.CameraModes
import id.homebase.core.camera.rememberInAppCameraManager
import io.github.vinceglb.filekit.PlatformFile

// Emits the camera dialog while open, so call it unconditionally, never inside an `if`.
// With awaitResultShown a capture stays on screen until the receiver calls the launcher's resultShown.
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

    /** Opens the camera grown out of [origin], the launching control's bounds in window coordinates, and shrinks it back there on close. */
    fun launchFrom(origin: Rect) = launch()

    /** Tells a camera held over the app that the capture it handed off is on screen. */
    fun resultShown() = Unit
}

private object NoCameraManager : PlatformCameraManager {
    override fun launch() = Unit
}
