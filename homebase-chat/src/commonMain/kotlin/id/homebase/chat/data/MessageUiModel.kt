package id.homebase.chat.data

import androidx.compose.runtime.Immutable
import id.homebase.api.client.drives.files.ReactionSummary
import id.homebase.chat.services.MessageAppData
import kotlin.time.Instant
import kotlin.uuid.Uuid

@Immutable
data class MessageUiModel(
    val id: Uuid, // uniqueId
    val conversationId: Uuid, // groupId
    val content: String, // the message
    val timestamp: Instant, // When the message was sent
    val senderId: String, // TODO: What is that? The name?
    val senderOdinId: String, // frodo.baggins.demo.rocks
    val isCurrentUser: Boolean = false, // TODO: What is that?
    val isRead: Boolean = false,
    val isEdited: Boolean = false,
    val messageAppData: MessageAppData, // TODO: Should we copy these up into the message?
    val reactionPreview: ReactionSummary?
)