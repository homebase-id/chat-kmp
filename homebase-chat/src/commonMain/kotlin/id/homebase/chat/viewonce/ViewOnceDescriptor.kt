package id.homebase.chat.viewonce

import kotlinx.serialization.Serializable

/**
 * Wire format for a view-once media message. Rides in `appData.content` next to
 * `appData.dataType = ChatProtocol.ChatViewOnceMessageDataType`; the single media payload lives
 * under `chat_web0`. Identity, author and time come from the HomebaseFile envelope.
 */
@Serializable
data class ViewOnceDescriptor(
    val kind: String,
    val schemaVersion: Int = 1,
) {
    fun isValid(): Boolean = kind == KIND_IMAGE || kind == KIND_VIDEO

    fun summaryLine(): String = if (kind == KIND_VIDEO) "View-once video" else "View-once photo"

    companion object {
        const val KIND_IMAGE = "image"
        const val KIND_VIDEO = "video"
    }
}
