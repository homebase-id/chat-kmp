package id.homebase.core.notifications

import kotlin.uuid.Uuid

/**
 * Resolves a chat message's notification preview (sender + text) IN-APP on push receipt (#859),
 * so an Android notification can show real content instead of the "You have a new message"
 * placeholder. Implemented in `homebase-chat` (which owns the message decrypt + typed-preview
 * pipeline) and injected here — `homebase-common` can't depend on `homebase-chat`, so the
 * dependency is inverted through this narrow seam.
 *
 * Returns null when the message can't be resolved or decrypted (not synced and fetch failed,
 * unmappable, etc.); the caller falls back to the generic body. Content is decrypted on-device
 * — nothing plaintext is sent through the server.
 */
interface NotificationMessageResolver {
    /** [withImage] false skips the thumbnail fetch, for a content level that won't show it. */
    suspend fun resolvePreview(conversationId: Uuid, messageId: Uuid, withImage: Boolean): NotificationMessagePreview?
}

/** [groupTitle] is null for a 1:1 conversation. [imageBytes] is the message's own photo, never an avatar. */
class NotificationMessagePreview(
    val senderName: String?,
    val preview: String,
    val groupTitle: String? = null,
    val imageBytes: ByteArray? = null,
)
