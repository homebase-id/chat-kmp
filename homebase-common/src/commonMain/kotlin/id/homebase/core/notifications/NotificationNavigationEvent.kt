package id.homebase.core.notifications

/** Events emitted by NotificationService when a notification is tapped. Observed by AppNavHost. */
sealed class NotificationNavigationEvent {
    data class OpenConversation(val conversationId: String) : NotificationNavigationEvent()
    data class OpenUrl(val url: String) : NotificationNavigationEvent()
}
