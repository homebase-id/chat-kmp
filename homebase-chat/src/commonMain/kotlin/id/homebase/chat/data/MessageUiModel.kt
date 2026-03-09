package id.homebase.chat.data

import androidx.compose.runtime.Immutable
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.client.drives.files.ReactionSummary
import id.homebase.api.client.drives.upload.EmbeddedThumb
import id.homebase.api.common.OdinId
import id.homebase.chat.services.MessageAppData
import kotlinx.collections.immutable.ImmutableList
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
    val created: Instant, // When the message was created by the author
    val modified: Instant?, // When the message was last modified
    val originalAuthor: OdinId?,
    val displayName: String,
    val isRead: Boolean = false,
    val isEdited: Boolean = false,
    val messageAppData: MessageAppData, // TODO: Should we copy these up into the message?
    val reactionPreview: ReactionSummary?,
    /** Tiny blurry preview thumbnail of the file */
    val previewThumbnail: EmbeddedThumb?,
    /** List of payload descriptors with metadata */
    val payloads: ImmutableList<PayloadDescriptor>?,

    val keyHeader: KeyHeader,
    val isDeleted: Boolean = false,
    val versionTag: Uuid,

    /** When true the item exists in the local-sync database only, most likey because it
     * was optimistically written but not yet sent */
    val isPendingSend: Boolean
) {
    fun isCurrentUser(domain: OdinId?): Boolean = (originalAuthor == domain)

}