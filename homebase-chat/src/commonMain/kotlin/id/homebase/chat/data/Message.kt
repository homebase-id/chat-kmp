package id.homebase.chat.data

import androidx.compose.runtime.Immutable
import kotlin.time.Instant

@Immutable
data class Message(
    val id: String,
    val conversationId: String,
    val content: String,
    val timestamp: Instant,
    val senderId: String,
    val senderName: String,
    val isCurrentUser: Boolean = false,
    val isRead: Boolean = false
)
