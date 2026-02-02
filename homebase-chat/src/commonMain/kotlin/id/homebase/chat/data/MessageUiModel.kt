package id.homebase.chat.data

import androidx.compose.runtime.Immutable
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.client.drives.files.ReactionSummary
import id.homebase.api.client.drives.upload.EmbeddedThumb
import id.homebase.chat.services.MessageAppData
import kotlin.time.Instant
import kotlin.uuid.Uuid

@Immutable
data class MessageUiModel(
    val id: Uuid, // uniqueId
    /** GlobalTransitId of the payload - same across all recipients */
    val globalTransitId: Uuid?,
    /** FileId of the payload - different for each server */
    val fileId: Uuid, // fileId
    val conversationId: Uuid, // groupId
    val content: String, // the message
    val created: Instant, // When the message was sent
    val modified: Instant?, // When the message was last modified
    val senderId: String, // TODO: What is that? The name?
    val senderOdinId: String, // frodo.baggins.demo.rocks
    val isRead: Boolean = false,
    val isEdited: Boolean = false,
    val messageAppData: MessageAppData, // TODO: Should we copy these up into the message?
    val reactionPreview: ReactionSummary?,
    /** Tiny blurry preview thumbnail of the file */
    val previewThumbnail: EmbeddedThumb?,
    /** List of payload descriptors with metadata */
    val payloads: List<PayloadDescriptor>?,

    val keyHeader: KeyHeader
    ) {
    fun isCurrentUser(domain: String): Boolean =
        senderOdinId.trim().equals(domain.trim(), ignoreCase = true)

}