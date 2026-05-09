package id.homebase.chat.services.convo

import id.homebase.api.common.OdinId
import id.homebase.api.util.truncateToCodePoints
import id.homebase.chat.data.ConversationUiModel
import id.homebase.chat.data.MessageUiModel
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Pure function that applies one incoming chat-message arrival to the
 * in-memory conversation list.
 *
 * Returns `null` when the message doesn't require a UI change (the
 * targeted conversation isn't in the list, or the message is older
 * than the current `latestMessageTimestamp`). Returns the new list
 * otherwise.
 *
 * Why this is its own function: the equivalent logic used to delegate
 * to [ConversationStream.updateConversation], which silently drops
 * `unreadCount` because it's designed for whole-file refreshes (those
 * always carry `incoming.unreadCount = 0` from the mapper). Going
 * through that helper masked the unread bump and the bug only
 * surfaced once the dirty-bit gating tightened the
 * `enrichAllConversationsWithUnreadCounts` cadence so the in-memory
 * `++` stopped being constantly overwritten by a fresh DB recount.
 *
 * Extracting the bump as a pure function makes the unread-count
 * accumulation testable in isolation (no DB, no Koin, no
 * coroutines). See `ConversationMessageBumpTest`.
 *
 * The map callback reads `existing` (live state) instead of any
 * captured-from-caller snapshot so consecutive bumps for the same
 * conversation in one batch are additive — the caller's loop applies
 * messages one at a time, and each call sees the previous call's
 * write through the returned list.
 *
 * @param items                  the current in-memory list.
 * @param targetConversationId   the conversation [m] belongs to.
 * @param m                      the incoming message (already mapped).
 * @param sqlUserDate            authoritative `DriveMainIndex.userDate`
 *                               for the message file. Used as the
 *                               source of truth for the conversation's
 *                               `latestMessageTimestamp` so it stays in
 *                               lock-step with `selectAllUnreadCount`.
 * @param activeDomain           the local identity, for the
 *                               not-authored-by-self test that gates
 *                               the unread bump.
 * @return                       the new list, or null if the message
 *                               doesn't trigger a change.
 */
internal fun applyIncomingMessageBump(
    items: List<ConversationUiModel>,
    targetConversationId: Uuid,
    m: MessageUiModel,
    sqlUserDate: Instant,
    activeDomain: OdinId?,
): List<ConversationUiModel>? {
    val increment =
        if (!m.isEdited && !m.isAuthoredBy(activeDomain) && !m.isStatusMessage) 1 else 0

    var didChange = false
    val updated = items.map { existing ->
        if (existing.id != targetConversationId) return@map existing
        if (sqlUserDate < existing.latestMessageTimestamp) return@map existing
        didChange = true
        existing.copy(
            unreadCount = existing.unreadCount + increment,
            latestMessageTimestamp = sqlUserDate,
            lastMessage = m.content.truncateToCodePoints(40),
            lastMessageDeliveryStatus = m.messageAppData.deliveryStatus,
            lastMessageIsDeleted = m.isDeleted,
            lastMessageFirstPayload = m.payloads?.firstOrNull(),
            lastMessageHasMultiplePayloads = (m.payloads?.size ?: 0) > 1,
            lastMessageIsFromActiveUser = m.isAuthoredBy(activeDomain),
        )
    }
    return if (didChange) updated else null
}
