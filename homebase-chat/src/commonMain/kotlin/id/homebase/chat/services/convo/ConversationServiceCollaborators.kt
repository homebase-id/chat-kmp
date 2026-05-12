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
        additionalRecipients: List<OdinId> = emptyList(),
        /** When non-null, replaces the conversation's participants term in the
         *  recipient calculation — the status message is delivered only to
         *  this set (minus self). Used by the targeted-heal path to address
         *  GroupHealRequested at only the Stale recipients. Null = legacy
         *  broadcast to every participant. */
        recipientOverride: List<OdinId>? = null,
    ): SendMessageResult
}

interface ConversationLoader {
    suspend fun loadConversation(conversationId: Uuid)
    /** Drop the in-memory entry for [conversationId]. No-op if not present. */
    suspend fun removeConversation(conversationId: Uuid)
}

/**
 * Narrow read-only view of conversation participant state, exposed so that
 * services that need to look up "who is in this conversation" don't have to
 * depend on the full [ConversationStream] (which carries a heavy graph of
 * dependencies and can't be subclassed in tests).
 *
 * Implemented by [ConversationStream]; faked in test fixtures.
 */
interface ConversationParticipantLookup {
    fun getConversationById(conversationId: Uuid): id.homebase.chat.data.ConversationUiModel?
    suspend fun getRecipients(
        conversationId: Uuid,
        additionalRecipients: List<OdinId> = emptyList(),
        /** When non-null, used in place of the conversation's participants list.
         *  The self-domain is still filtered out and the result is deduplicated.
         *  Lets the heal path address messages at a specific subset of peers. */
        recipientOverride: List<OdinId>? = null,
    ): List<OdinId>
}
