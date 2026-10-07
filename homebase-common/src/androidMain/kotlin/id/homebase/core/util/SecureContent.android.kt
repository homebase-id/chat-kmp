package id.homebase.core.util

import android.view.Window
import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import java.util.WeakHashMap

@Composable
actual fun SecureWindowEffect(active: Boolean) {
    val window = (LocalView.current.parent as? DialogWindowProvider)?.window ?: LocalActivity.current?.window
    DisposableEffect(window, active) {
        if (window != null && active) window.holdSecure()
        onDispose { if (window != null && active) window.releaseSecure() }
    }
}

@Composable
actual fun rememberScreenCaptureObserver(onScreenshot: () -> Unit): State<Boolean> = remember { mutableStateOf(false) }

actual val screenCaptureBlockedBySystem: Boolean = true

// Main thread only: Compose effects are already serialised there.
private val secureHolds = WeakHashMap<Window, SecureFlagRefCounter>()

private fun Window.holdSecure() {
    val counter = secureHolds.getOrPut(this) { SecureFlagRefCounter() }
    val alreadySet = attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
    if (counter.acquire(alreadySet)) addFlags(WindowManager.LayoutParams.FLAG_SECURE)
}

private fun Window.releaseSecure() {
    val counter = secureHolds[this] ?: return
    if (counter.release()) clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    if (!counter.isHeld) secureHolds.remove(this)
}
