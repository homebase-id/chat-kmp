@file:OptIn(ExperimentalForeignApi::class)

package id.homebase.core.camera

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.CoreMotion.CMMotionManager
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIDevice
import platform.UIKit.UIDeviceOrientation
import platform.UIKit.UIDeviceOrientationDidChangeNotification
import platform.UIKit.UIInterfaceOrientationLandscapeLeft
import platform.UIKit.UIInterfaceOrientationLandscapeRight
import platform.UIKit.UIInterfaceOrientationPortraitUpsideDown
import platform.UIKit.UIApplication
import androidx.compose.ui.platform.LocalWindowInfo

@Composable
internal actual fun rememberRawDeviceRotation(): QuarterTurn? {
    var turn by remember { mutableStateOf<QuarterTurn?>(null) }
    DisposableEffect(Unit) {
        val motion = CMMotionManager()
        // UIDevice orientation stops changing under the system portrait lock; gravity doesn't.
        if (motion.isAccelerometerAvailable()) {
            motion.accelerometerUpdateInterval = ACCELEROMETER_INTERVAL_S
            motion.startAccelerometerUpdatesToQueue(NSOperationQueue.mainQueue) { data, _ ->
                val degrees = data?.acceleration?.useContents { DeviceRotation.degreesForGravity(x, y, z) }
                if (degrees != null) DeviceRotation.quarterTurnFor(degrees, turn)?.let { turn = it }
            }
            onDispose { motion.stopAccelerometerUpdates() }
        } else {
            val device = UIDevice.currentDevice
            turn = device.orientation.quarterTurn()
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
    }
    return turn
}

private const val ACCELEROMETER_INTERVAL_S = 0.1

@Composable
actual fun rememberDisplayRotation(): QuarterTurn {
    // The interface rotating always resizes the Compose container, so the size is the change signal.
    val size = LocalWindowInfo.current.containerSize
    return remember(size) {
        when (UIApplication.sharedApplication.keyWindow?.windowScene?.interfaceOrientation) {
            UIInterfaceOrientationLandscapeLeft -> QuarterTurn.R90
            UIInterfaceOrientationLandscapeRight -> QuarterTurn.R270
            UIInterfaceOrientationPortraitUpsideDown -> QuarterTurn.R180
            else -> QuarterTurn.R0
        }
    }
}

/** Face up/down and unknown carry no rotation, so the last one sticks. */
private fun UIDeviceOrientation.quarterTurn(): QuarterTurn? = when (this) {
    UIDeviceOrientation.UIDeviceOrientationPortrait -> QuarterTurn.R0
    UIDeviceOrientation.UIDeviceOrientationLandscapeRight -> QuarterTurn.R90
    UIDeviceOrientation.UIDeviceOrientationPortraitUpsideDown -> QuarterTurn.R180
    UIDeviceOrientation.UIDeviceOrientationLandscapeLeft -> QuarterTurn.R270
    else -> null
}
