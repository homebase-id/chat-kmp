package id.homebase.core.notifications

/**
 * Platform-agnostic data for displaying a rich notification.
 * Passed to platform-specific RichNotificationDisplayer implementations.
 */
data class RichNotificationData(
    /** Notification ID. Use conversationId.hashCode() for chat so messages stack per conversation. */
    val notificationId: Int,
    /** Android notification channel: "messages", "social", or "feed". */
    val channelId: String,
    /** Non-null for chat/community conversations — used for grouping and navigation. */
    val conversationId: String?,
    val title: String,
    val body: String,
    val senderName: String,
    val senderId: String,
    /** Circular avatar bytes fetched from https://{senderId}/pub/image. */
    val senderImageBytes: ByteArray?,
    val timestamp: Long,
    /** Full payload data for round-tripping through notification tap. */
    val payloadData: Map<String, String>,
    val isGroupConversation: Boolean = false,
    val groupTitle: String? = null,
    /** When true, the notification should not play a sound (chime cooldown active). */
    val silent: Boolean = false,
    /**
     * True only when [body] is a real (decrypted) message — not the sender-side
     * "You have a new message" placeholder or a NotificationBodyFormer fallback.
     * Gates the Mark-as-Read action (#983); set once notification decryption lands (#859).
     */
    val hasContent: Boolean = false,
    /**
     * True only at the `name_content_actions` content level, where real message text is shown.
     * Tells the Android displayer to stack the conversation's recent messages (MessagingStyle
     * history) instead of collapsing to a "$N new messages" count. Redacted levels keep the count.
     */
    val showsRealContent: Boolean = false,
)

/** Extension to generate a stable notification ID from the conversation ID or a random one. */
internal val PushNotificationPayloadOptions.conversationNotificationId: Int
    get() = typeId.hashCode().let { if (it == 0) kotlin.random.Random.nextInt() else it }
