package id.homebase.core.notifications

/** Platform-specific badge count management for app icon badges. */
expect object BadgeManager {
    fun increment()

    /** Sets the badge to an absolute [count]. */
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
     * Dismisses a single conversation's posted notifications and its group summary,
     * leaving all other conversations' notifications in place.
     */
    fun cancelConversationNotifications(conversationId: String)
}
