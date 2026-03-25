package id.homebase.core.notifications

import platform.UIKit.UIApplication

/** iOS badge management via UIApplication.applicationIconBadgeNumber. */
actual object BadgeManager {

    actual fun increment() {
        val app = UIApplication.sharedApplication
        app.applicationIconBadgeNumber = app.applicationIconBadgeNumber + 1
    }

    actual fun clear() {
        UIApplication.sharedApplication.applicationIconBadgeNumber = 0
    }
}
