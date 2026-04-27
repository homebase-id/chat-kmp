package id.homebase.core.notifications

/** Events emitted by NotificationService when a notification is tapped. Observed by AppNavHost. */
sealed class NotificationNavigationEvent {
    data class OpenConversation(
        val conversationId: String,
        val source: Source = Source.NotificationTap,
    ) : NotificationNavigationEvent() {
        // Notification taps wait on PendingNotificationTap (needs a messageId + drive sync).
        // Share intents have no messageId — they need direct ChatList selection so the
        // attached content reaches the conversation detail screen.
        enum class Source { NotificationTap, ShareIntent }
    }
    data class OpenUrl(val url: String) : NotificationNavigationEvent()
}
