package id.homebase.core.util

import androidx.compose.runtime.Composable
import id.homebase.core.camera.CameraModes
import id.homebase.core.camera.rememberInAppCameraManager
import io.github.vinceglb.filekit.PlatformFile

@Composable
actual fun rememberCameraManager(
    modes: CameraModes,
    onOpenGallery: (() -> Unit)?,
    onResult: (PlatformFile?) -> Unit,
): PlatformCameraManager = rememberInAppCameraManager(allowedModes = modes, onOpenGallery = onOpenGallery, onResult = onResult)
