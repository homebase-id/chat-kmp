package id.homebase.chat.data

import androidx.compose.runtime.Immutable
import id.homebase.api.client.drives.files.ThumbnailDescriptor
import id.homebase.api.util.truncateToCodePoints
import kotlin.time.Instant
import kotlin.uuid.Uuid

@Immutable
data class ConversationUiModel(  // TODO: Move the data objects / classes into Conversation.kt ?
    val id: Uuid,
    val name: String,
    var lastMessage: String,
    var timestamp: Instant, // Timestamp of the last message in this convo
    var unreadCount: Int = 0,
    val avatarInitials: String,
    val avatarUrl: String = "",
    val avatarTiny: ThumbnailDescriptor?,
    val participants: List<String> = listOf(),
    val isPinned: Boolean = false,
    val lastRead: Instant
) {
    fun updateWithLatestMessage(msg : MessageUiModel)
    {
        // TODO: Should we also increase unread count here if it's a new message?
        if (msg.timestamp >= timestamp)
        {
            lastMessage = msg.messageAppData.message.truncateToCodePoints(40)
            timestamp = msg.timestamp
        }
    }
}