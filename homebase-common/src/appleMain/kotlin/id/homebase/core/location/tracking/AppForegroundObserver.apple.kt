package id.homebase.core.location.tracking

import co.touchlab.kermit.Logger
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIApplicationDidEnterBackgroundNotification

private val logger = Logger.withTag("AppForegroundObserver")

actual fun observeAppForeground(onChange: (Boolean) -> Unit) {
    val center = NSNotificationCenter.defaultCenter
    // Registration happens during application(_:didFinishLaunching:) — before
    // UIApplicationDidBecomeActive is posted — so reporting background here is
    // correct for both launch shapes: a visible launch receives DidBecomeActive
    // moments later, and a background location-key relaunch correctly stays
    // in the background profile.
    onChange(false)
    center.addObserverForName(
        name = UIApplicationDidBecomeActiveNotification,
        `object` = null,
        queue = NSOperationQueue.mainQueue,
    ) { _ ->
        logger.i { "iOS -> FOREGROUND (DidBecomeActive)" }
        onChange(true)
    }
    center.addObserverForName(
        name = UIApplicationDidEnterBackgroundNotification,
        `object` = null,
        queue = NSOperationQueue.mainQueue,
    ) { _ ->
        logger.i { "iOS -> BACKGROUND (DidEnterBackground)" }
        onChange(false)
    }
}
