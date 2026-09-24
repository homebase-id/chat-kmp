package id.homebase.core.camera

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import platform.AVKit.AVCaptureEventInteraction
import platform.AVKit.AVCaptureEventPhase
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIAccessibilityIsReduceMotionEnabled
import platform.UIKit.UIAccessibilityReduceMotionStatusDidChangeNotification
import platform.UIKit.UIApplication
import platform.UIKit.addInteraction
import platform.UIKit.removeInteraction

@Composable
internal actual fun rememberReduceMotion(): Boolean {
    var reduce by remember { mutableStateOf(UIAccessibilityIsReduceMotionEnabled()) }
    DisposableEffect(Unit) {
        val observer = NSNotificationCenter.defaultCenter.addObserverForName(
            name = UIAccessibilityReduceMotionStatusDidChangeNotification,
            `object` = null,
            queue = NSOperationQueue.mainQueue,
        ) { _ -> reduce = UIAccessibilityIsReduceMotionEnabled() }
        onDispose { NSNotificationCenter.defaultCenter.removeObserver(observer) }
    }
    return reduce
}

@Composable
internal actual fun HardwareShutterEffect(onDown: () -> Unit, onUp: () -> Unit) {
    val currentOnDown by rememberUpdatedState(onDown)
    val currentOnUp by rememberUpdatedState(onUp)
    DisposableEffect(Unit) {
        val view = UIApplication.sharedApplication.keyWindow?.rootViewController?.view
        val interaction = AVCaptureEventInteraction { event ->
            when (event?.phase) {
                AVCaptureEventPhase.AVCaptureEventPhaseBegan -> currentOnDown()
                AVCaptureEventPhase.AVCaptureEventPhaseEnded, AVCaptureEventPhase.AVCaptureEventPhaseCancelled -> currentOnUp()
                else -> Unit
            }
        }
        view?.addInteraction(interaction)
        onDispose { view?.removeInteraction(interaction) }
    }
}
