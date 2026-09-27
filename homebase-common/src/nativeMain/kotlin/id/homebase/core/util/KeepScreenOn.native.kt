package id.homebase.core.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import platform.UIKit.UIApplication

@Composable
actual fun KeepScreenOn(active: Boolean) {
    DisposableEffect(active) {
        if (active && awakeHolds++ == 0) UIApplication.sharedApplication.idleTimerDisabled = true
        onDispose {
            if (active && --awakeHolds == 0) UIApplication.sharedApplication.idleTimerDisabled = false
        }
    }
}

// idleTimerDisabled is app-global, so every holder shares one count; main thread only.
private var awakeHolds = 0
