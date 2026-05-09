package id.homebase.chat.services

import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.common.BatchResult
import id.homebase.chat.data.MessageUiModel
import kotlin.uuid.Uuid

/**
 * Narrow read-side seam over ChatMessageStream. Keeps ChatMessageActionService
 * and ChatMessageSenderService buildable in tests without the full
 * message-stream collaborator graph (ContactService, DriveFileProvider,
 * EventBus subscribers, scope).
 */
interface MessageLookup {
    suspend fun getMessage(messageId: Uuid): MessageUiModel?
    suspend fun getMessages(messageIds: List<Uuid>): BatchResult<MessageUiModel>
    /** Server-side header file for a message. Used by edit/reply paths to
     *  resolve the underlying [HomebaseFile] when we need its versionTag. */
    suspend fun getMessageFile(messageId: Uuid): HomebaseFile?
    /** For messages whose content was offloaded to a payload (over the
     *  in-header size budget), read the full text back. */
    suspend fun loadFullMessage(conversationId: Uuid, messageId: Uuid): String?

    /** Cache-only lookup. Returns the drive `fileId` for [messageId] if the
     *  message is currently in the in-memory state (i.e. its conversation is
     *  open and loaded), otherwise `null`. Cheap, synchronous-equivalent —
     *  callers fall through to a DB lookup on `null` rather than treating it
     *  as "not found". */
    fun findCachedFileId(messageId: Uuid): Uuid?
}
