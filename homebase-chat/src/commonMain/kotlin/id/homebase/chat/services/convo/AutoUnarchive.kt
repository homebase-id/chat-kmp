package id.homebase.chat.services.convo

import id.homebase.chat.data.ConversationState
import kotlin.time.Instant

/**
 * Cold half of auto-unarchive (#1145): decides from persisted state alone, so it
 * still fires after a restart or a silent DriveSync catch-up — neither of which
 * emits the `BatchReceived` the live half in
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
