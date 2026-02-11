package id.homebase.chat.data

import androidx.compose.runtime.Immutable
import co.touchlab.kermit.Logger
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.client.drives.files.ReactionSummary
import id.homebase.api.client.drives.upload.EmbeddedThumb
import id.homebase.chat.services.MessageAppData
import kotlin.time.Instant
import kotlin.uuid.Uuid
import id.homebase.api.common.OdinId
import id.homebase.api.serialization.OdinSystemSerializer

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
    val originalAuthorOdinId: OdinId?, // TODO: What is that? The name? <-- this is originalAuthorId misnamed likely
    val senderOdinId: OdinId?, // The message author, e.g. frodo.baggins.demo.rocks
    val isRead: Boolean = false,
    val isEdited: Boolean = false,
    val messageAppData: MessageAppData, // TODO: Should we copy these up into the message?
    val reactionPreview: ReactionSummary?,
    /** Tiny blurry preview thumbnail of the file */
    val previewThumbnail: EmbeddedThumb?,
    /** List of payload descriptors with metadata */
    val payloads: List<PayloadDescriptor>?,

    val keyHeader: KeyHeader // TODO: Todd <-- make it simple and just store the key? (if we use the IV elsewhere that's kind of a bug)
) {
    fun isCurrentUser(domain: OdinId?): Boolean = (senderOdinId == domain)

    fun getEmoji(reactionContent: String?): String {
        if (reactionContent.isNullOrBlank()) return ""

        return runCatching {
            OdinSystemSerializer
                .deserialize<ReactionContent>(reactionContent)
                .emoji
        }.getOrDefault("")
    }

    fun getAllEmojis(): List<Pair<String, Int>> {
        val TAG = "MessageUiModel"

        val summary = reactionPreview ?: return emptyList()

        val results = summary.reactions.values.mapNotNull { entry ->
            val emoji = getEmoji(entry.reactionContent)
            if (emoji.isBlank()) {
                Logger.w(TAG) { "Invalid emoji content: ${entry.reactionContent}" }
                null
            } else {
                Logger.d(TAG) { "Emoji parsed: $emoji (count=${entry.count})" }
                emoji to entry.count
            }
        }

        Logger.d(TAG) { "Total emojis rendered: ${results.size}" }

        return results
    }


}