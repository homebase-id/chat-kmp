package id.homebase.core.notifications

/** Platform-specific badge count management for app icon badges. */
expect object BadgeManager {
    fun increment()

    /**
     * Resets the in-memory badge counter to 0 without dismissing any posted
     * notifications. Use on app resume so the icon-badge total stays accurate
     * while leaving the tray intact (the user clears notifications per
     * conversation by tapping or reading them).
     */
    fun resetCount()

    /** Resets the counter AND dismisses every posted notification. Logout only. */
    fun cancelAll()

    /**
     * Dismisses a single conversation's posted notification and its group
     * summary, leaving all other conversations' notifications in place.
     * [messageId] and [summaryId] come from [conversationNotificationIds].
     */
    fun cancelConversationNotifications(messageId: Int, summaryId: Int)
}
