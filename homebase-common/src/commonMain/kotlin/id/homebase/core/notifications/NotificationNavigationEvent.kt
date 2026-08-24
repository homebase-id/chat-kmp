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

    /**
     * Open the contact-detail screen for [odinId] (the requester) so an incoming connection
     * request can be reviewed and accepted in-app. Emitted for a tapped owner-app
     * connection-request push, which would otherwise bounce out to the owner web console
     * (`/owner/connections`) in the browser — the app can review it itself, including the
     * add-to-circles picker on accept. The screen shows the requester's public profile with
     * Accept/Reject as long as the request is still pending.
     */
    data class OpenConnectionRequest(val odinId: String) : NotificationNavigationEvent()

    /**
     * Open the moments detail (Instagram-Reels-style vertical pager) landing on
     * [momentId]. Emitted for a tapped moment-post or comment notification (both
     * ride on the chat appId; see NotificationService.resolveMomentsTap).
     * [openComments] is true for a comment notification so the detail opens with
     * its comment thread expanded.
     */
    data class OpenMoment(
        val momentId: String,
        val openComments: Boolean = false,
    ) : NotificationNavigationEvent()

    /**
     * Open the moments composer (`Route.MomentCompose`). Emitted when the user
     * shares media into "New Moment" from the OS share sheet: the share flow
     * has already seeded [MomentCreateFlowState] with the chosen attachments, so
     * this event only steers the back stack into the composer.
     */
    data object OpenMomentCompose : NotificationNavigationEvent()
}
