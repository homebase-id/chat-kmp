package id.homebase.core.camera

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.window.DialogProperties
import platform.UIKit.UIApplication
import platform.UIKit.UIDevice
import platform.UIKit.UIDeviceOrientation
import platform.UIKit.UIInterfaceOrientationMask
import platform.UIKit.UIInterfaceOrientationMaskLandscapeLeft
import platform.UIKit.UIInterfaceOrientationMaskLandscapeRight
import platform.UIKit.setNeedsUpdateOfSupportedInterfaceOrientations
import platform.UIKit.UIInterfaceOrientationMaskPortrait
import platform.UIKit.UIUserInterfaceIdiomPhone
import platform.UIKit.UIWindowSceneGeometryPreferencesIOS

internal actual fun cameraDialogProperties(): DialogProperties = DialogProperties(
    dismissOnClickOutside = false,
    usePlatformDefaultWidth = false,
    usePlatformInsets = false,
    scrimColor = Color.Transparent,
)

/** Read by the app delegate's supportedInterfaceOrientationsFor, the only place iOS takes a per-screen lock from. */
object CameraOrientationLock {
    var portraitOnly: Boolean = false
        internal set
}

@Composable
internal actual fun CameraWindowEffect() {
    DisposableEffect(Unit) {
        // iPad keeps rotating like the system camera; the HUD switches to its side rail there.
        val lock = UIDevice.currentDevice.userInterfaceIdiom == UIUserInterfaceIdiomPhone
        if (lock) {
            CameraOrientationLock.portraitOnly = true
            applyOrientationLock()
        }
        onDispose {
            if (lock) {
                CameraOrientationLock.portraitOnly = false
                applyOrientationLock()
            }
        }
    }
}

private fun applyOrientationLock() {
    val window = UIApplication.sharedApplication.keyWindow ?: return
    window.rootViewController?.setNeedsUpdateOfSupportedInterfaceOrientations()
    // After a forced portrait UIKit won't rotate back on its own until the device moves again.
    val mask = if (CameraOrientationLock.portraitOnly) UIInterfaceOrientationMaskPortrait else landscapeMaskForDevice()
    if (mask != null) {
        window.windowScene?.requestGeometryUpdateWithPreferences(
            UIWindowSceneGeometryPreferencesIOS(mask),
            errorHandler = null,
        )
    }
}

// Device and interface landscape are mirrored: device LandscapeLeft = interface LandscapeRight.
private fun landscapeMaskForDevice(): UIInterfaceOrientationMask? = when (UIDevice.currentDevice.orientation) {
    UIDeviceOrientation.UIDeviceOrientationLandscapeLeft -> UIInterfaceOrientationMaskLandscapeRight
    UIDeviceOrientation.UIDeviceOrientationLandscapeRight -> UIInterfaceOrientationMaskLandscapeLeft
    else -> null
}
