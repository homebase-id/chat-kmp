package id.homebase.chat.data

import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

@Serializable
data class SendChatMessageRequest(
    val conversationId: Uuid,
    val messageText: String,
    val recipients: List<String>
)