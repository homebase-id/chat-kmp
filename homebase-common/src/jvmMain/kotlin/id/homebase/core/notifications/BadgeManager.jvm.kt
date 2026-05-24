package id.homebase.core.notifications

/** Desktop: No badge support; toasts can't be cancelled individually. */
actual object BadgeManager {
    actual fun increment() { /* no-op */ }
    actual fun resetCount() { /* no-op */ }
    actual fun cancelAll() { /* no-op */ }
    actual fun cancelConversationNotifications(messageId: Int, summaryId: Int) { /* no-op */ }
}
