package id.homebase.chat.services.convo

import id.homebase.chat.data.ConversationState
import id.homebase.chat.data.ConversationUiModel
import kotlin.time.Instant

/**
 * Result of merging a conversation-FILE refresh into an in-memory model.
 *
 * @property merged          the reconciled conversation model.
 * @property isNewlyRemoved  true when this merge is the first time we observe
 *                           the local identity dropping out of a group. The
 *                           caller owns the side effect (stamp `exitedAt` into
 *                           localAppData + enqueue the outbox push); the pure
 *                           merge only reports it.
 */
internal data class ConversationFileMergeResult(
    val merged: ConversationUiModel,
    val isNewlyRemoved: Boolean,
)

/**
 * Pure merge of a conversation-FILE refresh ([incoming]) into the current
 * in-memory model ([existing]).
 *
 * Extracted out of [ConversationStream.updateConversation] for the same reason
 * [applyIncomingMessageBump] was: so the reconciliation rules can be unit-tested
 * with no DB, Koin, or coroutines. The caller keeps the side effects (the
 * newly-removed `exitedAt` stamp + outbox push, and writing the result into the
 * StateFlow); everything that decides the *values* lives here.
 *
 * ## Message-preview ownership
 *
 * The message-preview fields (`lastMessage`, `lastMessageDeliveryStatus`,
 * `lastMessageIsDeleted`, `lastMessageFirstPayload`,
 * `lastMessageHasMultiplePayloads`, `lastMessageIsFromActiveUser`) and the
 * `latestMessageTimestamp` sort key are **owned exclusively by the message
 * pipeline** — [applyIncomingMessageBump] (live arrivals) and
 * [ConversationMapper.applyLastMessage] (cold-load / WS-batch enrichment). A
 * conversation FILE never carries real preview data: it is mapped via
 * `mapToConversationUi(file, lastMsg = null)`, so `ConversationMapper.mapToBasic`
 * hardcodes `lastMessage = " "`. This merge therefore leaves all of those fields
 * on their `existing` values and only reconciles structural state (name,
 * participants, membership, pinned, lastRead/dirty).
 *
 * Why this matters: before the persisted-sort-key change (commit 2e0d4e76) this
 * merge copied the preview fields under an
 * `incoming.latestMessageTimestamp >= existing.latestMessageTimestamp` guard.
 * That guard was accidentally safe only because `incoming` carried
 * `metadata.created` (older than the message-driven `existing`). Once
 * `mapToBasic` began seeding `latestMessageTimestamp` from the persisted
 * `localAppData` value — stamped on every send/read — a post-send conv-file echo
 * arrives with a timestamp *equal to* `existing`, the guard flips true, and the
 * `" "` placeholder wipes the real preview. See ConversationFileMergeTest.
 *
 * @param now used only for the newly-Removed `exitedAt` stamp; passed in so the
 *            function stays pure (no clock read).
 */
internal fun mergeConversationFileUpdate(
    existing: ConversationUiModel,
    incoming: ConversationUiModel,
    now: Instant,
): ConversationFileMergeResult {
    // Left is sticky — only cleared by an explicit rejoin action, not by outbox
    // responses which may not preserve localAppData tags. RejoinPending is the
    // one exception: it means the server shows us back in participants while we
    // still have the Left tag locally.
    val resolvedState = if (existing.conversationState == ConversationState.Left
        && incoming.conversationState != ConversationState.Left
        && incoming.conversationState != ConversationState.RejoinPending
    ) {
        ConversationState.Left
    } else {
        incoming.conversationState
    }

    // First observed Removed transition — caller stamps lastExitedAt into
    // localAppData so the mapper reads it back on later loads.
    val isNewlyRemoved = resolvedState == ConversationState.Removed
            && existing.conversationState != ConversationState.Removed
            && existing.conversationState != ConversationState.Left

    // Clear exitedAt when active again; preserve on Left/Removed (first stamp wins).
    val resolvedExitedAt = when {
        resolvedState == ConversationState.Active
                || resolvedState == ConversationState.RejoinPending -> null

        isNewlyRemoved -> now
        else -> existing.exitedAt ?: incoming.exitedAt
    }

    // Carries the conversation file's localAppData.lastReadTime forward — a peer
    // device's mark-as-read (or our own pushed advance echoing back) advances it
    // and syncs it. lastRead = max keeps it monotonic against a stale echo; dirty
    // (we still owe a server push) is retired once the remote read reaches our
    // local value — see reconciledWithRemoteLastRead. Must override dirty
    // explicitly: the existing.copy base would otherwise preserve a now-stale flag.
    val reconciled = existing.reconciledWithRemoteLastRead(incoming.lastRead)

    // Structural fields (membership, identity) always come from the conversation
    // file, regardless of timestamp. The in-memory timestamp is driven by message
    // arrivals and is almost always newer than metadata.created, so a timestamp
    // guard would silently drop participant/admin/name changes distributed by
    // peers (e.g. leave, add member).
    //
    // Message-preview fields and latestMessageTimestamp are NOT merged here: a
    // conversation file never carries real preview data (mapToBasic seeds
    // lastMessage = " "). They stay on `existing`, owned by the message pipeline.
    val merged = existing.copy(
        name = incoming.name,
        isGroup = incoming.isGroup,
        isLegacyGroup = incoming.isLegacyGroup,
        admins = incoming.admins,
        avatarModel = incoming.avatarModel,
        avatarTiny = incoming.avatarTiny,
        avatarUrl = incoming.avatarUrl,
        avatarInitials = incoming.avatarInitials,
        participants = incoming.participants,
        isPinned = incoming.isPinned,
        // The draft rides the conversation file's localAppData, so the fresh file
        // owns it — carry it from `incoming` or a live edit / clear on another
        // device (or this one) wouldn't update the list row. #1122.
        draft = incoming.draft,
        conversationState = resolvedState,
        exitedAt = resolvedExitedAt,
        fileUpdated = incoming.fileUpdated,
        lastRead = reconciled.lastRead,
        dirty = reconciled.dirty,
    )

    return ConversationFileMergeResult(merged = merged, isNewlyRemoved = isNewlyRemoved)
}
