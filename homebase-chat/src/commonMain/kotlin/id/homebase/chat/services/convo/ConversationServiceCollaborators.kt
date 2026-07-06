package id.homebase.chat.services.convo

import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.files.ArchivalStatus
import id.homebase.api.common.OdinId
import id.homebase.api.common.time.UnixTimeUtc
import id.homebase.chat.data.ConversationUiModel
import id.homebase.upload.PayloadBundle
import id.homebase.chat.services.SendMessageResult
import id.homebase.chat.services.StatusMessageData
import kotlin.time.Instant
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
 * Narrow view of [ConversationService] needed by [GroupHealService]: the
 * cross-file read paths plus the redistribute and placeholder writers that
 * live on ConversationService for cohesion with non-heal flows (the
 * conversation-placeholder writer is shared with `recoverConversation`).
 *
 * Implemented by [ConversationService]; faked in heal tests.
 */
interface GroupHealConversationOps {
    suspend fun requireConversation(conversationId: Uuid): ConversationUiModel
    suspend fun getConversation(conversationId: Uuid): ConversationUiModel?
    suspend fun getConversationHomebaseFile(conversationId: Uuid): HomebaseFile?
    suspend fun getConversationAdminHomebaseFile(conversationId: Uuid): HomebaseFile?

    /** Send-side resend; heal calls with `distributeOnlyTo` set to a Missing-peer subset
     *  and `preserveExistingPayloads = true` to keep the conversation image attached. */
    suspend fun updateConversationInternal(
        conversationId: Uuid,
        title: String?,
        participants: List<OdinId>,
        payloadBundle: PayloadBundle? = null,
        dependencyUniqueId: Uuid? = null,
        archivalStatus: ArchivalStatus? = null,
        distribute: Boolean = true,
        additionalDistributionRecipients: List<OdinId> = emptyList(),
        isGroup: Boolean? = null,
        applyOptimisticContentLocally: Boolean = false,
        distributeOnlyTo: List<OdinId>? = null,
        preserveExistingPayloads: Boolean = false,
    )

    suspend fun updateAdminFile(
        conversationId: Uuid,
        admins: List<OdinId>,
        recipients: List<OdinId>,
    )

    /** Shared with `recoverConversation`; lives on ConversationService. */
    suspend fun writeOrReplaceConversationPlaceholder(
        conversationId: Uuid,
        brokenFileIdToDelete: Uuid?,
        participants: List<OdinId>,
        isGroup: Boolean,
        title: String,
        audit: MethodAudit,
    )

    suspend fun writeOrReplaceAdminPlaceholder(
        conversationId: Uuid,
        brokenFileIdToDelete: Uuid?,
        admins: List<OdinId>,
        audit: MethodAudit,
    )
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

    // region lastRead dirty tracking
    // The in-memory conversation list is the single source of truth for both
    // the lastRead value and whether it still owes a server push (the `dirty`
    // flag on ConversationUiModel). A local advance sets both together via
    // [advancedLastRead] (which applies [ConversationUiModel.advancedLastRead]
    // to the list); the deferred writeback flush in ConversationService reads
    // and clears them here.

    /**
     * List-level counterpart of [ConversationUiModel.advancedLastRead] — same
     * name on purpose. Runs the entity's advance transition (move lastRead +
     * set dirty, together) on the in-memory entry for [conversationId] and
     * commits it back into the live list, re-deriving unreadCount in the same
     * emission. No-op if the conversation isn't in memory or [candidate] isn't
     * a genuine advance. The setter calls this so the in-memory model, its
     * dirty flag, and the flush it schedules all move as one.
     */
    suspend fun advancedLastRead(conversationId: Uuid, candidate: Instant)

    /** Ids of conversations whose lastRead is dirty (owes a server push). */
    fun getDirtyConversationIds(): List<Uuid>

    /** Clear [conversationId]'s dirty flag, but only if its current lastRead
     *  still equals [pushed] — i.e. nothing advanced it while the writeback was
     *  in flight. If a newer advance landed, the flag stays set so the newer
     *  value gets pushed next round. Called by the flush after a successful
     *  push; on push failure the caller simply doesn't call this, leaving the
     *  conversation dirty for retry. Compare-and-clear, so no value-less
     *  re-flagging is ever needed. */
    suspend fun clearLastReadDirtyIfUnchanged(conversationId: Uuid, pushed: UnixTimeUtc)
    // endregion
}
