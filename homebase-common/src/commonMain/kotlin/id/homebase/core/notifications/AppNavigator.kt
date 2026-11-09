package id.homebase.core.notifications

interface AppNavigator {
    fun navigateToConversation(
        conversationId: String,
        source: NotificationNavigationEvent.OpenConversation.Source =
            NotificationNavigationEvent.OpenConversation.Source.NotificationTap,
    )

    fun navigateToMomentCompose()

    fun navigateToWebDropCompose()
}
