package id.homebase.core.util

import androidx.compose.runtime.Composable
import id.homebase.core.camera.CameraModes
import io.github.vinceglb.filekit.PlatformFile

@Composable
actual fun rememberCameraManager(
    modes: CameraModes,
    onOpenGallery: (() -> Unit)?,
    onResult: (PlatformFile?) -> Unit,
): PlatformCameraManager = NoCameraManager

private object NoCameraManager : PlatformCameraManager {
    override fun launch() = Unit
}
