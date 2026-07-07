package id.homebase.chat.conversationlist

import id.homebase.core.util.ScrollPosition
import kotlin.uuid.Uuid

/**
 * Helpers for the index ↔ uniqueId mapping that anchored scroll persistence
 * needs. The list shape is stable across sessions only at the message level —
 * separators, headers, and unread markers are rebuilt from message data on
 * each open and are not stable anchors.
 *
 * Used by [id.homebase.chat.widget.ConversationMessagesPane] (when persisting
 * the current scroll position — index → anchor) and by
 * [ConversationListViewModel.getScrollPosition] (when restoring — anchor →
 * index).
 */

/**
 * Walk forward from [fromIndex] until a [MessageListContentModel.Message] is
 * reached and return its uniqueId, or `null` if none exists at or after that
 * position. Walking forward (not backward) means a date-separator at the top
 * of the visible window resolves to the FIRST message below it — which is
 * what the user is actually reading.
 */
internal fun resolveAnchorMessageId(
    messages: List<MessageListContentModel>,
    fromIndex: Int,
): Uuid? {
    if (fromIndex < 0) return null
    var i = fromIndex
    while (i < messages.size) {
        val item = messages[i]
        if (item is MessageListContentModel.Message) {
            return item.message.id
        }
        i++
    }
    return null
}

/**
 * Find the index of the [MessageListContentModel.Message] whose uniqueId
 * matches [anchorMessageId]. Returns `null` if the anchor isn't in the list
 * (e.g. the message was deleted or hasn't been synced down yet) — the
 * caller should fall back to the default landing position in that case.
 */
internal fun resolveAnchorIndex(
    messages: List<MessageListContentModel>,
    anchorMessageId: Uuid,
): Int? {
    var i = 0
    while (i < messages.size) {
        val item = messages[i]
        if (item is MessageListContentModel.Message && item.message.id == anchorMessageId) {
            return i
        }
        i++
    }
    return null
}

/**
 * After a "scroll to latest" reload swaps the in-memory window to the newest
 * page, the Pane's [androidx.compose.foundation.lazy.LazyListState] still
 * points at the stale history index it had while the user was paged backwards.
 * Produce an explicit bottom anchor (`triggerScroll = true`) at the last
 * message so [id.homebase.chat.widget.ConversationMessagesPane]'s
 * scroll-trigger effect jumps to the newest message instead of leaving the
 * user mid-window. Returns `null` when there are no message rows to land on.
 *
 * Walks back from the end to the last [MessageListContentModel.Message] rather
 * than using `lastIndex`: after the reload `hasNewerMessages` is false so there
 * is no trailing `LoadingNewer` row, but this stays correct even if the model
 * list ever ends in a separator or other non-message row.
 */
internal fun resolveScrollToLatestPosition(
    messages: List<MessageListContentModel>,
): ScrollPosition? {
    val lastMessageIndex = messages.indexOfLast { it is MessageListContentModel.Message }
    if (lastMessageIndex < 0) return null
    return ScrollPosition(firstVisibleItemIndex = lastMessageIndex, triggerScroll = true)
}

/**
 * Gate for the one-time own-send follow ([MessageListUiState.scrollToLatestRequest]).
 *
 * A plain-text/reply/location send has no optimistic placeholder — the message
 * reaches [messages] asynchronously via the optimistic-write round-trip — so the
 * consuming effect in `ConversationContent` must not scroll until (a) the requested
 * message is actually in the merged data (as a real message or a pending
 * placeholder) and (b) the LazyColumn has laid out at least that many items.
 * Scrolling any earlier targets the previous last item and leaves the send below
 * the fold (#995).
 *
 * Returns the laid-out item count whose last index (`count - 1`) is the scroll
 * target, or `null` while the insert/layout hasn't caught up yet.
 */
internal fun resolveOwnSendFollowTarget(
    requestedId: Uuid,
    messages: List<MessageListContentModel>,
    pendingOutgoing: List<PendingOutgoingMessage>,
    conversationId: Uuid,
    laidOutItemCount: Int,
): Int? {
    val realIds = messages.asSequence()
        .filterIsInstance<MessageListContentModel.Message>()
        .mapTo(HashSet()) { it.message.id }
    // Mirrors ConversationContent's mergedItems interleave: a placeholder counts
    // only for this conversation and only until its real message lands.
    val pendingForConvo = pendingOutgoing.filter {
        it.conversationId == conversationId && it.id !in realIds
    }
    val present = requestedId in realIds || pendingForConvo.any { it.id == requestedId }
    val mergedCount = messages.size + pendingForConvo.size
    return if (present && laidOutItemCount >= mergedCount) laidOutItemCount else null
}
