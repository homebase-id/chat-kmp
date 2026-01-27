package id.homebase.chat.data

import androidx.compose.runtime.Immutable
import id.homebase.api.client.drives.HomebaseFile
import kotlin.time.Instant
import kotlin.uuid.Uuid

@Immutable
data class Message(
    val id: Uuid,
    val conversationId: Uuid,
    val content: String,
    val timestamp: Instant,
    val senderId: String,
    val senderOdinId: String,
    val isCurrentUser: Boolean = false,
    val isRead: Boolean = false,
    val messageAppData: MessageAppData
) {
    companion object {
        /**
         * Convert HomebaseFile to Message object
         * Handles fileType 7878 (chat messages)
         */
        fun fromHomebaseFile(homebaseFile: HomebaseFile): Message? {
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

                Message(
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
