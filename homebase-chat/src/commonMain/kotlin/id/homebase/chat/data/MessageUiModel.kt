package id.homebase.chat.data

import androidx.compose.runtime.Immutable
import id.homebase.api.client.drives.HomebaseFile
import kotlin.time.Instant
import kotlin.uuid.Uuid

@Immutable
data class MessageViewModel(
    val id: Uuid, // uniqueId
    val conversationId: Uuid, // groupId
    val content: String, // the message
    val timestamp: Instant, // When the message was sent
    val senderId: String, // TODO: What is that? The name?
    val senderOdinId: String, // frodo.baggins.demo.rocks
    val isCurrentUser: Boolean = false, // TODO: What is that?
    val isRead: Boolean = false,
    val messageAppData: MessageAppData // TODO: Should we copy these up into the message?
) {
    companion object {
        /**
         * Convert HomebaseFile to Message object
         * Handles fileType 7878 (chat messages)
         */
        fun fromHomebaseFile(homebaseFile: HomebaseFile): MessageViewModel? {
            return try {
                val metadata = homebaseFile.fileMetadata
                val appData = metadata.appData

                if (appData.fileType != CHAT_MESSAGE_FILE_TYPE)
                    throw IllegalArgumentException("HomebaseFile must be of type Chat_message")

                if (metadata.senderOdinId == null)
                    throw IllegalArgumentException("SenderId must be set")

                if (appData.content == null)
                    throw IllegalArgumentException("AppData is empty")

                if (appData.uniqueId == null)
                    throw IllegalArgumentException("UniqueId is empty")

                if (appData.groupId == null)
                    throw IllegalArgumentException("GroupId is empty")

                val messageAppData = MessageAppData.fromMessageAppDataJson(appData.content!!)

                MessageViewModel(
                    id = appData.uniqueId!!,
                    conversationId = appData.groupId!!,
                    content = messageAppData.message,
                    timestamp = metadata.transitCreated.toInstant(),
                    senderId = metadata.senderOdinId!!,
                    senderOdinId = "Lookup Contacts", // Lookup in contacts via senderOdinId
                    isCurrentUser = false,
                    isRead = false,
                    messageAppData = messageAppData
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}
