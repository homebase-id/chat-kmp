package id.homebase.core.camera

import androidx.compose.runtime.Composable
import androidx.compose.ui.window.DialogProperties

internal actual fun cameraDialogProperties(): DialogProperties =
    DialogProperties(dismissOnClickOutside = false, usePlatformDefaultWidth = false)

@Composable
internal actual fun CameraWindowEffect() = Unit

@Composable
internal actual fun KeepScreenOnEffect(enabled: Boolean) = Unit
