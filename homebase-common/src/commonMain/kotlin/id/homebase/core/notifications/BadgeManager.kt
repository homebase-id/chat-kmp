package id.homebase.core.notifications

/** Platform-specific badge count management for app icon badges. */
expect object BadgeManager {
    fun increment()

    /**
     * Sets the badge to an absolute [count]. On iOS this also rewrites the App Group
     * value the Notification Service Extension increments from, so the next push counts
     * up from the app's truth instead of from a drifted running total.
     */
    fun setCount(count: Int)

    /**
     * Clears the badge, leaving posted notifications in the tray. Not the app-resume
     * path — resuming with messages still unread must keep showing them, so that path
     * calls [setCount] with the real total.
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
