package id.homebase.chat.data

import id.homebase.api.client.drives.upload.EmbeddedThumb
import id.homebase.homebasekmppoc.prototype.lib.serialization.OdinSystemSerializer
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/** Data class representing chat message homebaseFile.AppData (parsed from JSON) */
@Serializable
data class MessageAppData(
    /** Optional reply ID if this message is a reply to another message -
     *  OBSOLETE - only on old messages */
    val replyId: Uuid? = null,
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

    companion object {
        /**
         * Parse JSON string as MessageAppData
         */
        fun fromMessageAppDataJson(messageAppData: String): MessageAppData {
            return try {
                // Parse JSON as MessageAppData
                OdinSystemSerializer.deserialize<MessageAppData>(messageAppData)
            } catch (e: Exception) {
                throw e
            }
        }
    }
}

@Serializable
data class ReplyPreview(
    val replyUniqueId: Uuid, // FileId of the message that was replied to
    val authorOdinId: String, // frodo.baggins.demo.rocks
    val message: String, // ~40 chars (IDK how many you use?)
    val previewThumbnail: EmbeddedThumb
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
        fun fromValue(value: Int): ChatDeliveryStatus? =
            ChatDeliveryStatus.entries.find { it.value == value }
    }
}