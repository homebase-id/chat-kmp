package id.homebase.core.camera

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIDevice
import platform.UIKit.UIDeviceOrientation
import platform.UIKit.UIDeviceOrientationDidChangeNotification

@Composable
internal actual fun rememberRawDeviceRotation(): QuarterTurn? {
    var turn by remember { mutableStateOf(UIDevice.currentDevice.orientation.quarterTurn()) }
    DisposableEffect(Unit) {
        val device = UIDevice.currentDevice
        device.beginGeneratingDeviceOrientationNotifications()
        val observer = NSNotificationCenter.defaultCenter.addObserverForName(
            name = UIDeviceOrientationDidChangeNotification,
            `object` = device,
            queue = NSOperationQueue.mainQueue,
        ) { _ -> device.orientation.quarterTurn()?.let { turn = it } }
        onDispose {
            NSNotificationCenter.defaultCenter.removeObserver(observer)
            device.endGeneratingDeviceOrientationNotifications()
        }
    }
    return turn
}

/** Face up/down and unknown carry no rotation, so the last one sticks. */
private fun UIDeviceOrientation.quarterTurn(): QuarterTurn? = when (this) {
    UIDeviceOrientation.UIDeviceOrientationPortrait -> QuarterTurn.R0
    UIDeviceOrientation.UIDeviceOrientationLandscapeRight -> QuarterTurn.R90
    UIDeviceOrientation.UIDeviceOrientationPortraitUpsideDown -> QuarterTurn.R180
    UIDeviceOrientation.UIDeviceOrientationLandscapeLeft -> QuarterTurn.R270
    else -> null
}
