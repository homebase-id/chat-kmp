package id.homebase.core.notifications

/** Web: No badge support. */
actual object BadgeManager {
    actual fun increment() { /* no-op */ }
    actual fun resetCount() { /* no-op */ }
    actual fun cancelAll() { /* no-op */ }
    actual fun cancelConversationNotifications(messageId: Int, summaryId: Int) { /* no-op */ }
}
