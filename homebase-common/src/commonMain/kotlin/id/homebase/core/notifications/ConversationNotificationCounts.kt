package id.homebase.core.notifications

/**
 * Tracks the per-conversation count of undismissed notifications, used to render
 * the "$n new messages" summary body. A conversation's count is reset whenever
 * its notifications are cleared — by opening the conversation, tapping its
 * notification, marking it read, or logging out — so the next incoming message
 * restarts the count at 1 rather than inflating a stale total.
 *
 * Not thread-safe; all access happens on the NotificationService coroutine scope.
 */
class ConversationNotificationCounts {

    private val counts = mutableMapOf<String, Int>()

    /** Increments the count for [conversationId] and returns the new running total. */
    fun increment(conversationId: String): Int {
        val next = (counts[conversationId] ?: 0) + 1
        counts[conversationId] = next
        return next
    }

    /** Current count for [conversationId], or 0 if none is tracked. */
    fun peek(conversationId: String): Int = counts[conversationId] ?: 0

    /** Forgets the count for [conversationId] (e.g. on tap, open, or mark-as-read). */
    fun clear(conversationId: String) {
        counts.remove(conversationId)
    }

    /** Forgets every conversation's count (e.g. on logout). */
    fun clearAll() {
        counts.clear()
    }
}
