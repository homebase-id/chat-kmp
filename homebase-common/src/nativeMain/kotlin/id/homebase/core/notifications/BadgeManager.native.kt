package id.homebase.core.notifications

import platform.Foundation.NSBundle
import platform.Foundation.NSUserDefaults
import platform.UserNotifications.UNUserNotificationCenter

/**
 * iOS badge management. Every write mirrors the count into the App Group so the
 * Notification Service Extension — a separate process that runs even while the app is
 * killed — can increment it and stamp `content.badge`.
 */
actual object BadgeManager {

    actual fun increment() {
        setCount(sharedCount() + 1)
    }

    actual fun setCount(count: Int) {
        val clamped = count.coerceAtLeast(0)
        sharedDefaults?.setInteger(clamped.toLong(), BADGE_COUNT_KEY)
        // setBadgeCount, not UIApplication.applicationIconBadgeNumber: the latter is
        // deprecated on iOS 17+ and main-thread-only.
        UNUserNotificationCenter.currentNotificationCenter().setBadgeCount(clamped.toLong(), null)
    }

    actual fun resetCount() {
        setCount(0)
    }

    actual fun cancelAll() {
        setCount(0)
    }

    // The iOS Notification Service Extension owns per-notification display; there
    // is no Kotlin-side handle to cancel an individual conversation's notification.
    actual fun cancelConversationNotifications(messageId: Int, summaryId: Int) { /* no-op */ }

    private fun sharedCount(): Int = (sharedDefaults?.integerForKey(BADGE_COUNT_KEY) ?: 0L).toInt()

    // The group id differs between the dev and release builds, so it must come from the
    // build-setting-expanded Info.plist — a hardcoded literal silently no-ops on debug.
    private val sharedDefaults: NSUserDefaults? =
        (NSBundle.mainBundle.infoDictionary?.get("AppGroupIdentifier") as? String)
            ?.let { NSUserDefaults(suiteName = it) }

    // Keep in sync with badgeCountKey in NotificationService.swift.
    private const val BADGE_COUNT_KEY = "unread_badge_count"
}
