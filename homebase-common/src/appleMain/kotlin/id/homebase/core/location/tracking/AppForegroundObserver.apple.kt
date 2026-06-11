package id.homebase.core.location.tracking

import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIApplicationStateActive

actual fun observeAppForeground(onChange: (Boolean) -> Unit) {
    val center = NSNotificationCenter.defaultCenter
    onChange(UIApplication.sharedApplication.applicationState == UIApplicationStateActive)
    center.addObserverForName(
        name = UIApplicationDidBecomeActiveNotification,
        `object` = null,
        queue = NSOperationQueue.mainQueue,
    ) { _ -> onChange(true) }
    center.addObserverForName(
        name = UIApplicationDidEnterBackgroundNotification,
        `object` = null,
        queue = NSOperationQueue.mainQueue,
    ) { _ -> onChange(false) }
}
