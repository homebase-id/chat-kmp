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

    /**
     * Batch lookup of messages by uniqueId, scoped to a known conversation.
     *
     * Why [conversationId] is mandatory: without it, the underlying QueryBatch
     * SQL becomes `uniqueId IN (?)` ordered by `CreatedDate` with no group
     * filter, and the planner falls back to a global timeline scan (the same
     * stat-less pathology that #606 cured for the per-conversation chat fetch).
     * Passing the conversation lets QueryBatch's chat-shape branch fire and
     * pin `idx_chatmessage_convid_userDate` via INDEXED BY — the lookup
     * becomes a tight per-conversation seek regardless of how many other
     * conversations exist. The cost is one extra parameter at the call site;
     * the benefit is that no caller can accidentally re-introduce the
     * full-timeline scan.
     */
    suspend fun getMessages(
        messageIds: List<Uuid>,
        conversationId: Uuid,
    ): BatchResult<MessageUiModel>
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
