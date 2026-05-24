package id.homebase.core.notifications

import platform.UIKit.UIApplication

/** iOS badge management via UIApplication.applicationIconBadgeNumber. */
actual object BadgeManager {

    actual fun increment() {
        val app = UIApplication.sharedApplication
        app.applicationIconBadgeNumber = app.applicationIconBadgeNumber + 1
    }

    actual fun resetCount() {
        UIApplication.sharedApplication.applicationIconBadgeNumber = 0
    }

    actual fun cancelAll() {
        UIApplication.sharedApplication.applicationIconBadgeNumber = 0
    }

    // The iOS Notification Service Extension owns per-notification display; there
    // is no Kotlin-side handle to cancel an individual conversation's notification.
    actual fun cancelConversationNotifications(messageId: Int, summaryId: Int) { /* no-op */ }
}
