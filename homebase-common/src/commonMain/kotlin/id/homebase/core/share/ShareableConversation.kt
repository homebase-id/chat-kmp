package id.homebase.core.share

import kotlinx.serialization.Serializable

/**
 * Lightweight conversation metadata for the share extension conversation picker.
 * Contains only what's needed to render a selectable list — no message content,
 * encryption keys, or sensitive data.
 */
@Serializable
data class ShareableConversation(
    val id: String,
    val displayName: String,
    val avatarInitials: String,
    val isGroup: Boolean,
    val participantCount: Int,
    val lastMessageTimestamp: Long,
    val avatarUrl: String? = null,
)

/**
 * Container for the conversation cache written to shared storage.
 * iOS share extension reads this; Android reads from DB directly.
 */
@Serializable
data class ShareConversationCacheData(
    val conversations: List<ShareableConversation>,
    val updatedAt: Long,
    val userDomain: String,
    // Whether the Moments feature is activated for this user. The iOS share
    // extension reads this to decide whether to offer "New Moment" as a share
    // destination (it can't reach the main app's DB). Defaults false so older
    // caches deserialize cleanly.
    val momentsActivated: Boolean = false,
)
