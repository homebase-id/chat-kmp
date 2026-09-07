package id.homebase.chat.conversationlist

import kotlin.uuid.Uuid

/**
 * The most recently active conversation, not the first row: pinned conversations sit above the
 * rest and rarely change, so anchoring on row order would mean a list with any pin almost never
 * detects a reorder. Keying on the sort value instead covers both sections with one marker.
 */
internal fun resolveTopConversationId(items: List<ConversationListContentModel>): Uuid? =
    items.mapNotNull { (it as? ConversationListContentModel.Conversation)?.conversation?.conversation }
        .maxByOrNull { it.latestMessageTimestamp }
        ?.id

/** A null snapshot is a list the user has never seen (fresh install): landing at the top then
 *  would be a scroll nobody asked for. */
internal fun shouldScrollToTop(
    snapshotTopId: Uuid?,
    currentTopId: Uuid?,
    isAtTop: Boolean,
): Boolean {
    if (isAtTop || snapshotTopId == null || currentTopId == null) return false
    return snapshotTopId != currentTopId
}
