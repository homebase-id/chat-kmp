package id.homebase.core.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIApplicationUserDidTakeScreenshotNotification
import platform.UIKit.UIScreen
import platform.UIKit.UIScreenCapturedDidChangeNotification

@Composable
actual fun SecureWindowEffect(active: Boolean) = Unit

@Composable
actual fun rememberScreenCaptureObserver(onScreenshot: () -> Unit): State<Boolean> {
    val captured = remember { mutableStateOf(UIScreen.mainScreen.captured) }
    val latestOnScreenshot by rememberUpdatedState(onScreenshot)
    DisposableEffect(Unit) {
        val center = NSNotificationCenter.defaultCenter
        val captureObserver = center.addObserverForName(
            name = UIScreenCapturedDidChangeNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue,
        ) { _ -> captured.value = UIScreen.mainScreen.captured }
        val shotObserver = center.addObserverForName(
            name = UIApplicationUserDidTakeScreenshotNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue,
        ) { _ -> latestOnScreenshot() }
        captured.value = UIScreen.mainScreen.captured
        onDispose {
            center.removeObserver(captureObserver)
            center.removeObserver(shotObserver)
        }
    }
    return captured
}
