package id.homebase.core.camera

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.window.DialogProperties
import platform.UIKit.UIApplication

internal actual fun cameraDialogProperties(): DialogProperties = DialogProperties(
    dismissOnClickOutside = false,
    usePlatformDefaultWidth = false,
    usePlatformInsets = false,
    scrimColor = Color.Transparent,
)

@Composable
internal actual fun CameraWindowEffect() = Unit

@Composable
internal actual fun KeepScreenOnEffect(enabled: Boolean) {
    DisposableEffect(enabled) {
        UIApplication.sharedApplication.idleTimerDisabled = enabled
        onDispose { UIApplication.sharedApplication.idleTimerDisabled = false }
    }
}
