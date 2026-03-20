package id.homebase.chat.services

import id.homebase.api.client.drives.files.RichText
import id.homebase.api.client.drives.files.getPlainTextFromRichText
import id.homebase.api.serialization.OdinSystemSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlin.uuid.Uuid

/** Data class representing chat message homebaseFile.AppData (parsed from JSON) */
@Serializable
data class MessageAppData(
    @Transient val replyId: Uuid? = null, // OBSOLETE - needed for old data
    val replyPreview: ReplyPreview? = null,

    /** Content of the message - can be a simple string or rich text */
    val message: JsonElement = JsonPrimitive(""),

    // Where is the urlPreview?
    /** Delivery status of the message (as int value) */
    val deliveryStatus: Int = ChatDeliveryStatus.Sent.value,

    /** Whether the message has been edited */
    val isEdited: Boolean = false,

    /** The version of the MessageAppData stored
     * When version is null, the message field's content is likely rich-text coming
     *  from the older homebase apps (web and react-native)
     * When the version == 1, the message field's content is mark down
     */
    val version: Int? = null
) {

    fun getMessage(): String {
        return when {
            version == null -> getMessageAsString()
            version >= 1 -> getMessageAsMarkdown()
            else -> getMessageAsString() // safe fallback
        }
    }

    fun getMessageAsMarkdown(): String {
        return when (message) {
            is JsonPrimitive -> message.content
            else -> ""
        }
    }

    /** Returns the message as a plain string, extracting from RichText if necessary */
    fun getMessageAsString(): String {
        return when (message) {
            is JsonPrimitive -> message.content
            is JsonArray -> {
                try {
                    val richText =
                        OdinSystemSerializer.json.decodeFromJsonElement<RichText>(message)
                    getPlainTextFromRichText(richText) ?: ""
                } catch (e: Exception) {
                    "" // Fallback if decoding fails
                }
            }

            else -> ""
        }
    }
}