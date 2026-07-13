package id.homebase.chat.data

import androidx.compose.runtime.Immutable
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.client.drives.upload.EmbeddedThumb
import id.homebase.api.common.OdinId
import id.homebase.api.util.truncateToCodePoints
import id.homebase.chat.services.ChatProtocol
import id.homebase.chat.services.content.MessageContent
import id.homebase.core.avatars.ConversationAvatarModel
import kotlin.time.Instant
import kotlin.uuid.Uuid

@Immutable
data class ConversationUiModel(
    // TODO: Move the data objects / classes into Conversation.kt ?
    val id: Uuid,
    val name: String,
    val lastMessage: String,
    val latestMessageTimestamp: Instant, // Timestamp of the last message in this convo
    val unreadCount: Int = 0,
    val avatarInitials: String,
    val avatarUrl: String = "",
    val avatarTiny: EmbeddedThumb?,
    val participants: List<OdinId> = listOf(),
    val isPinned: Boolean = false,
    val lastRead: Instant,
    /**
     * True when [lastRead] has advanced locally but that advance hasn't been
     * pushed to the server yet. The deferred lastRead writeback flush walks
     * the conversation list, pushes the dirty ones, and clears the flag.
     * Bookkeeping for the writeback — not rendered by the UI.
     */
    val dirty: Boolean = false,
    val avatarModel: ConversationAvatarModel,
    val lastMessageDeliveryStatus: Int? = null,
    /**
     * True when the last message is still sitting in the outbox (pending send) —
     * the same [MessageUiModel.isPendingSend] signal the bubble uses. Without it
     * the list would map [lastMessageDeliveryStatus] (which defaults to Sent) to a
     * single check for a message that never actually sent. See issue #1076.
     */
    val lastMessageIsPendingSend: Boolean = false,
    val lastMessageIsDeleted: Boolean = false,
    val lastMessageFirstPayload: PayloadDescriptor? = null,
    val lastMessageHasMultiplePayloads: Boolean = false,
    /**
     * Parsed typed content of the last message (poll, event, dice roll, …) when it is one,
     * else null. Lets the list preview show the kind's icon + title via
     * [id.homebase.chat.widget.typedMessageContentLabel].
     */
    val lastMessageContent: MessageContent? = null,
    val lastMessageIsFromActiveUser: Boolean = false,
    val lastMessageSender: OdinId? = null,
    val admins: Set<OdinId>,
    val conversationState: ConversationState = ConversationState.Active,
    val isGroup: Boolean = false,
    val isLegacyGroup: Boolean = false,
    val exitedAt: Instant? = null,
    /** Server-stamped last-modified time of the conversation file (fileMetadata.updated). */
    val fileUpdated: Instant = Instant.fromEpochMilliseconds(0),
    /**
     * Server-stamped creation time of the conversation file (fileMetadata.created).
     * Sync-independent, so it's the reliable "chatting since" date — unlike the
     * oldest *locally-synced* message. Epoch 0 when unknown (e.g. an orphan
     * placeholder created before its real header synced).
     */
    val fileCreated: Instant = Instant.fromEpochMilliseconds(0),
) {
    fun isCurrentUserAdmin(odinId: OdinId): Boolean {
        return admins.contains(odinId)
    }

    /**
     * Mandatory gate for any caller about to advance lastRead. Returns
     * [candidate] if the advance has semantic effect, or null when the
     * write should be suppressed. Two suppression reasons:
     *
     *   1. **Monotonic** — lastRead never regresses. The candidate must
     *      be strictly greater than the current [lastRead].
     *   2. **Saturated** — [lastRead] is already at or past every known
     *      message ([latestMessageTimestamp]). Advancing further has no
     *      semantic effect: unread count is already 0, and peer devices
     *      can't act on "even more read." The conv-file stamp + outbox
     *      push that would follow would be pure noise.
     *
     * Callers MUST go through this — do NOT compare [candidate] to
     * [lastRead] ad-hoc. Centralising the rule here is what keeps the
     * mark-as-read flows (per-message, mark-all-as-read, future callers)
     * from drifting.
     */
    fun resolveLastReadAdvance(candidate: Instant): Instant? = when {
        lastRead >= latestMessageTimestamp -> null  // saturated
        candidate <= lastRead -> null                // not advancing
        else -> candidate
    }

    /**
     * if [candidate] is a genuine lastRead advance
     * (per [resolveLastReadAdvance]), return this with `lastRead` moved up AND
     * `dirty` set — together, so the flag and the value it represents can never
     * drift. Otherwise, returns this unchanged. The entity owns the rule;
     * immutable, so it returns the next state rather than mutating, and the
     * list owner swaps it in. `unreadCount` is layered on by the caller (it
     * needs a DB query the model can't do).
     */
    fun advancedLastRead(candidate: Instant): ConversationUiModel =
        resolveLastReadAdvance(candidate)?.let { copy(lastRead = it, dirty = true) } ?: this

    /**
     * Clear the dirty flag when pushing an update to the server but only if
     * its current [lastRead] still equals [pushedMs] (i.e. nothing advanced
     * while the writeback was in flight). If a newer advance landed, it stays
     * dirty so the newer value gets pushed next round. Compare-and-clear, expressed
     * on the entity so the "still at the pushed value?" rule lives with the data.
     */
    fun clearedDirtyIfUnchanged(pushedMs: Long): ConversationUiModel =
        if (dirty && lastRead.toEpochMilliseconds() == pushedMs) copy(dirty = false) else this

    /**
     * Reconcile this (local) model's [lastRead] + [dirty] against a
     * [remoteLastRead] carried by a conversation file just received from the
     * network (a peer device's mark-as-read, or our own pushed advance echoing
     * back). [lastRead] never regresses — take the max. [dirty] (we still owe
     * the server a push) is kept ONLY while our local read still leads the
     * remote; once the remote catches up (remote >= local) the server already
     * has our position, so the flag is stale — clear it.
     */
    fun reconciledWithRemoteLastRead(remoteLastRead: Instant): ConversationUiModel =
        copy(
            lastRead = maxOf(lastRead, remoteLastRead),
            dirty = dirty && lastRead > remoteLastRead,
        )

    /** True when this conversation is a group — either tagged or legacy (>2 participants). */
    val isGroupConversation: Boolean
        get() = isGroup || isLegacyGroup

    val isWithSelf: Boolean
        get() = id == ChatProtocol.ConversationWithYourselfId


    fun getDisplayName(): String {
        if (isGroupConversation) return name

        if (name.isEmpty() || name.isBlank()) {
            return participants.firstOrNull()?.domainName ?: ""
        }
        return name
    }

    companion object {
        /**
         * Patches the conversation's last-message fields if [msg] is at least
         * as recent as the current [latestMessageTimestamp].
         *
         * The "recency" comparison is driven by [latestTimestampOverrideMs]
         * when the caller knows the SQL-side userDate (e.g. from
         * `selectAllConversationPlusLastMessage`'s `msgUserDate` projection,
         * or from `HomebaseFile.sqlUserDateMs()` for files just written to
         * `DriveMainIndex`). This avoids the clamp inside
         * `ChatMessageStream.mapToMessageData`, which can drop
         * `MessageUiModel.userDate` below the SQL column when a peer's
         * `appData.userDate` exceeds `metadata.transitCreated` — and that
         * mismatch is what stuck Monica's unread badge at 1: the unread SQL
         * counted the row using the SQL userDate, but every read code path
         * advanced `lastReadTime` to the clamped (smaller) value.
         *
         * Falls back to `msg.userDate` when no override is supplied, matching
         * legacy behaviour for callers that haven't been migrated.
         */
        fun ConversationUiModel.updateWithLatestMessage(
            msg: MessageUiModel,
            activeUserDomain: OdinId?,
            latestTimestampOverrideMs: Long? = null,
        ): ConversationUiModel {
            val candidate = latestTimestampOverrideMs
                ?.let { Instant.fromEpochMilliseconds(it) }
                ?: msg.userDate
            if (candidate >= latestMessageTimestamp) {
                return this.copy(
                    lastMessage = msg.content.truncateToCodePoints(40),
                    latestMessageTimestamp = candidate,
                    lastMessageDeliveryStatus = msg.messageAppData.deliveryStatus,
                    lastMessageIsPendingSend = msg.isPendingSend,
                    lastMessageIsDeleted = msg.isDeleted,
                    lastMessageFirstPayload = msg.payloads?.firstOrNull(),
                    lastMessageHasMultiplePayloads = (msg.payloads?.size ?: 0) > 1,
                    lastMessageContent = msg.messageContent,
                    lastMessageIsFromActiveUser = msg.isFromActiveUser(activeUserDomain),
                    lastMessageSender = msg.originalAuthor,
                )
            }
            return this
        }
    }
}
