package id.homebase.core.camera

import android.content.pm.ActivityInfo
import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat

internal actual fun cameraDialogProperties(): DialogProperties = DialogProperties(
    dismissOnClickOutside = false,
    usePlatformDefaultWidth = false,
    decorFitsSystemWindows = false,
)

@Composable
internal actual fun CameraWindowEffect() {
    val view = LocalView.current
    val window = (view.parent as? DialogWindowProvider)?.window

    DisposableEffect(window) {
        if (window != null) {
            window.setDimAmount(0f)
            window.setWindowAnimations(0)
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
        onDispose { }
    }

    // The HUD stays portrait and counter-rotates its icons, like the system camera.
    val activity = LocalActivity.current
    DisposableEffect(activity) {
        val previous = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        onDispose {
            if (activity != null && previous != null) activity.requestedOrientation = previous
        }
    }
}
