package id.homebase.chat.conversationlist

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
