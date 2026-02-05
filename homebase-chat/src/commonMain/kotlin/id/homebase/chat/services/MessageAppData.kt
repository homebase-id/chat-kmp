package id.homebase.chat.services

import id.homebase.api.client.drives.files.ReactionSummary
import id.homebase.api.client.drives.upload.EmbeddedThumb
import id.homebase.api.common.time.UnixTimeUtc
import kotlin.uuid.Uuid
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/** Data class representing chat message homebaseFile.AppData (parsed from JSON) */
@Serializable
data class MessageAppData(
        @Transient val replyId: Uuid? = null, // OBSOLETE - needed for old data
        val replyPreview: ReplyPreview? = null,

        /** Content of the message - can be a simple string or rich text */
        val message: String = "",
        // homebaseFile.contentIsComplete is a boolean if true write "more..."
        // TODO: A helper function to load the text when the user presses "more..."

        // Where is the urlPreview?
        /** Delivery status of the message (as int value) */
        val deliveryStatus: Int = ChatDeliveryStatus.Sent.value,

        /** Whether the message has been edited */
        val isEdited: Boolean = false
) {
    /** Get the delivery status as enum */
    fun getDeliveryStatusEnum(): ChatDeliveryStatus? = ChatDeliveryStatus.fromValue(deliveryStatus)
}

@Serializable
data class ReplyPreview(
        val replyUniqueId: Uuid, // FileId of the message that was replied to
        val authorOdinId: String, // frodo.baggins.demo.rocks
        val message:
                String, // chopped chars (IDK how many you use? 40? 80? use truncateToCodePoints(80)
        val previewThumbnail: EmbeddedThumb? =
                null // Real thumb via replyUniqueId, null for text-only messages
) // Tiny tiny thumb, can be even smaller than tinyThumb even a 1px color

enum class ChatDeliveryStatus(val value: Int) {
    /** Message is currently being sent; Used for optimistic updates */
    Sending(15),

    /** Message has been sent and delivered to your identity */
    Sent(20),

    /** Message has been delivered to the recipient's inbox */
    Delivered(30),

    /** Message has been read by the recipient */
    Read(40),

    /** Message failed to send to the recipient */
    Failed(50);

    companion object {
        fun fromValue(value: Int): ChatDeliveryStatus? = entries.find { it.value == value }
    }
}

@Serializable
data class LinkPreview(
        val title: String,
        val url: String,
        val description: String,
        val imageUrl: String?,
        val imageHeight: Int?,
        val imageWidth: Int?,
) {
    fun getThumbUrl(): String {
        return ""
    }
}

@Serializable
data class ConversationLastMessageContent(
        val message: String?,
        val deliveryStatus: ChatDeliveryStatus,
        val sender: String,
        val uniqueId: Uuid,
        val time: UnixTimeUtc,
        val reactionSummary: ReactionSummary?,
)
