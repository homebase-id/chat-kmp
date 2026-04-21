package id.homebase.chat.services.convo

import id.homebase.api.common.OdinId
import id.homebase.chat.services.PayloadBundle
import id.homebase.chat.services.SendMessageResult
import id.homebase.chat.services.StatusMessageData
import kotlin.uuid.Uuid

interface StatusMessageSender {
    suspend fun sendStatusMessage(
        messageUniqueId: Uuid,
        conversationId: Uuid,
        statusMessage: StatusMessageData,
        previousMessageUniqueId: Uuid? = null,
        payloadBundle: PayloadBundle? = null,
        additionalRecipients: List<OdinId> = emptyList()
    ): SendMessageResult
}

interface ConversationLoader {
    suspend fun loadConversation(conversationId: Uuid)
}
