package id.homebase.chat.services.convo

import co.touchlab.kermit.Logger
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
 * targeted conversation isn't in the list, or the message's
 * `userDate` is not strictly newer than the conversation's current
 * `latestMessageTimestamp`). Returns the new list otherwise.
 *
 * The strict `>` (rather than `>=`) check is the guard against
 * reaction-driven re-emits. When someone reacts to a message, the
 * server pushes the modified message file (with the new
 * `reactionPreview` baked into the header) over the WS. That
 * re-emit has the SAME `userDate` as the original message
 * (`userDate` is the message's authored time — reactions don't
 * change it), so a `>=` check would incorrectly bump unread every
 * time anyone reacts. Strict `>` rejects re-emits and only accepts
 * messages with a newer `userDate` than the conversation has seen.
 *
 * Why this is its own function: the equivalent logic used to
 * delegate to [ConversationStream.updateConversation], which
 * silently drops `unreadCount` because it's designed for whole-file
 * refreshes (those always carry `incoming.unreadCount = 0` from the
 * mapper). Going through that helper masked the unread bump and
 * the bug only surfaced once the dirty-bit gating tightened the
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
        // Strict > so reaction-driven re-emits (which carry the same
        // userDate as the original message) don't bump unread.
        if (sqlUserDate <= existing.latestMessageTimestamp) return@map existing
        didChange = true
        val next = existing.copy(
            unreadCount = existing.unreadCount + increment,
            latestMessageTimestamp = sqlUserDate,
            lastMessage = m.content.truncateToCodePoints(40),
            lastMessageDeliveryStatus = m.messageAppData.deliveryStatus,
            lastMessageIsDeleted = m.isDeleted,
            lastMessageFirstPayload = m.payloads?.firstOrNull(),
            lastMessageHasMultiplePayloads = (m.payloads?.size ?: 0) > 1,
            lastMessageIsFromActiveUser = m.isAuthoredBy(activeDomain),
        )
        // Diagnostic for the asymmetric-badge investigation (plan
        // snazzy-frolicking-crown). Fires only when the strict-> check
        // passes, so reaction re-emits stay silent. Pairs with the
        // UnreadDiag lines in ConversationStream.enrichUnreadLocked
        // and is what lets us distinguish hypotheses H1 (lastRead
        // saturated) vs H2 (SQL excluded the row) on a real capture.
        Logger.d(tag = "UnreadDiag") {
            "bump convo=$targetConversationId sqlUserDateMs=${sqlUserDate.toEpochMilliseconds()} " +
                "originalAuthor=${m.originalAuthor?.domainName ?: "null"} " +
                "isAuthoredBy(self)=${m.isAuthoredBy(activeDomain)} " +
                "isEdited=${m.isEdited} isStatusMessage=${m.isStatusMessage} " +
                "increment=$increment unreadCount=${next.unreadCount}"
        }
        next
    }
    return if (didChange) updated else null
}

/**
 * Decide whether the `BackendEvent.DriveEvent.Stopped` handler should
 * run a fresh unread recount.
 *
 * Today's gate is `hasDirtyUnread()` alone — that fires only when
 * `processConversationBatchIncrementally` set the dirty bit, which itself
 * only runs on WS-push `BatchReceived`. The DriveSync QueryBatch path
 * intentionally never emits per-row events (see `DriveSync.kt:199-201`
 * "DriveSync is a silent batched DB write"), so message rows written by
 * BG sync — FCM cold-wake, post-reconnect catch-up, retries — never trip
 * the dirty bit and the badge stays stale (the original asymmetric-badge
 * bug, captured in homebase.log 2026-06-01 Session 5).
 *
 * The widened gate adds `isChatDrive && totalCount > 0` so the recount
 * runs whenever the chat drive's just-finished sync wrote anything to
 * `DriveMainIndex`. We deliberately do NOT gate on `result == Completed`:
 * the `BackendEvent.DriveResult` kdoc warns that earlier batches commit
 * before a later batch can Abort, so a chat-drive Aborted with
 * `totalCount > 0` still represents real rows in DB that affect the
 * count. Same for PermissionDenied (kdoc: "earlier batches' DB writes
 * commit before any later batch can fail").
 *
 * Cost when this widens the gate: one `selectAllUnreadCount` pass
 * (covering-index — measured 121-163 ms in homebase.log). Cheap enough
 * to pay per chat-drive Stopped that carried rows.
 *
 * Pure function so the gate is unit-testable without the ConversationStream
 * graph.
 *
 * @param isChatDrive    true if the Stopped event was for the chat drive
 *                       (other drives — contacts, vault, moments — never
 *                       affect chat unread counts).
 * @param totalCount     rows written to DriveMainIndex during this sync
 *                       round (across all file types — conversations,
 *                       messages, etc.). 0 ⇒ nothing landed ⇒ skip.
 * @param hasDirtyUnread the existing dirty bit, set by
 *                       `processConversationBatchIncrementally` on
 *                       WS-push conv-file arrivals.
 */
internal fun shouldRunUnreadRecountOnStopped(
    isChatDrive: Boolean,
    totalCount: Int,
    hasDirtyUnread: Boolean,
): Boolean = hasDirtyUnread || (isChatDrive && totalCount > 0)
