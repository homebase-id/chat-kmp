package id.homebase.core.notifications

import platform.UserNotifications.UNNotification
import platform.UserNotifications.UNUserNotificationCenter

/**
 * iOS badge management. The badge counts the notifications still sitting in the tray, so
 * the Notification Service Extension can recompute it from the tray on every push without
 * any state shared across the two processes.
 */
actual object BadgeManager {

    // iOS has no non-deprecated read of the current badge, and the extension already
    // counts every delivered push, so there is nothing here to add to.
    actual fun increment() { /* no-op */ }

    actual fun setCount(count: Int) {
        // setBadgeCount, not UIApplication.applicationIconBadgeNumber: the latter is
        // deprecated on iOS 17+ and main-thread-only.
        center().setBadgeCount(count.coerceAtLeast(0).toLong(), null)
    }

    actual fun resetCount() {
        setCount(0)
    }

    actual fun cancelAll() {
        center().removeAllDeliveredNotifications()
        setCount(0)
    }

    actual fun cancelConversationNotifications(conversationId: String) {
        val center = center()
        center.getDeliveredNotificationsWithCompletionHandler { delivered ->
            val tray = delivered.orEmpty().filterIsInstance<UNNotification>()
            val ids = tray
                .filter { it.request.content.threadIdentifier == conversationId }
                .map { it.request.identifier }
            if (ids.isEmpty()) return@getDeliveredNotificationsWithCompletionHandler

            center.removeDeliveredNotificationsWithIdentifiers(ids)
            // Subtracted rather than re-read: removeDelivered and getDelivered are not
            // ordered against each other, so a second read can still see the removed rows.
            setCount(tray.size - ids.size)
        }
    }

    private fun center() = UNUserNotificationCenter.currentNotificationCenter()
}
