package id.homebase.chat.services

import id.homebase.api.common.BatchResult
import id.homebase.chat.data.MessageUiModel
import kotlin.uuid.Uuid

/**
 * Narrow read-side seam over ChatMessageStream. Keeps ChatMessageActionService
 * buildable in tests without the full message-stream collaborator graph
 * (ContactService, DriveFileProvider, EventBus subscribers, scope).
 */
interface MessageLookup {
    suspend fun getMessage(messageId: Uuid): MessageUiModel?
    suspend fun getMessages(messageIds: List<Uuid>): BatchResult<MessageUiModel>
}
