package id.homebase.chat.services.convo

import id.homebase.chat.data.ConversationState
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Cold half of auto-unarchive: decides from persisted state alone, so it still
 * fires after a restart or a silent DriveSync catch-up — neither of which emits
 * the `BatchReceived` the live half in
 * `ConversationStream.processMessageBatchIncrementally` listens for.
 *
 * Callers must pass the last message from `selectAllConversationPlusLastMessage`,
 * which excludes `dataType = 202`, so a group rename can't unarchive a thread.
 *
 * A null [archivedAt] means the thread was archived before the stamp existed;
 * with no baseline every message looks new, so those are left alone.
 */
internal fun shouldAutoUnarchive(
    state: ConversationState,
    archivedAt: Instant?,
    lastMessageUserDate: Instant,
    lastMessageIsFromActiveUser: Boolean,
): Boolean =
    state == ConversationState.Archived &&
        archivedAt != null &&
        !lastMessageIsFromActiveUser &&
        lastMessageUserDate > archivedAt

/**
 * Dedup for the cold half. It runs on every sync `Stopped` and on every cold
 * start, so a tag removal that never reaches the local row would otherwise
 * re-fire — and re-enqueue — on every pass. Keyed on the baseline rather than
 * the id alone, so a later re-archive stamps a fresh [Instant] and re-opens the
 * gate. Session-scoped, like `ConversationStream.recoveryAttemptedIds`.
 */
internal class AutoUnarchiveGate {
    private val fired = mutableMapOf<Uuid, Instant>()

    fun markFired(conversationId: Uuid, archivedAt: Instant): Boolean =
        fired.put(conversationId, archivedAt) != archivedAt
}
