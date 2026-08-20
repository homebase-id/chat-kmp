package id.homebase.chat.services.convo

import id.homebase.api.client.KeyHeader
import id.homebase.api.common.OdinId
import id.homebase.api.common.time.UnixTimeUtc
import id.homebase.chat.data.ConversationState
import id.homebase.chat.data.ConversationUiModel
import id.homebase.chat.data.MessageUiModel
import id.homebase.chat.services.MessageAppData
import id.homebase.core.avatars.ConversationAvatarModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Locks down the unread-count bump path that lives in
 * [applyIncomingMessageBump].
 *
 * The regression this exists to guard against:
 * [ConversationStream.updateConversationFromNewMessage] used to delegate
 * to [ConversationStream.updateConversation] for the bump, but that
 * helper is shaped for whole-file refreshes and explicitly preserves
 * `existing.unreadCount` (because incoming-from-file always maps with
 * `unreadCount = 0`). The bug was masked while
 * `enrichAllConversationsWithUnreadCounts` ran on every `Stopped`, but
 * surfaced once the dirty-bit gating tightened that cadence — peer
 * messages would log `unread++ count=N` per arrival but the count
 * never actually persisted, freezing at whatever value the last
 * recount-from-DB landed.
 *
 * The most important test in this file is
 * [sequentialPeerMessages_accumulateUnread] — three pure
 * `applyIncomingMessageBump` calls in a row must yield unreadCount=3,
 * not 1.
 */
class ConversationMessageBumpTest {

    private val me = OdinId("owner.test")
    private val alice = OdinId("alice.test")
    private val convoId = Uuid.parse("11111111-1111-1111-1111-111111111111")
    private val otherConvoId = Uuid.parse("22222222-2222-2222-2222-222222222222")

    private fun convo(
        id: Uuid = convoId,
        latestMs: Long = 0L,
        unread: Int = 0,
    ) = ConversationUiModel(
        id = id,
        name = "alice",
        lastMessage = "",
        latestMessageTimestamp = Instant.fromEpochMilliseconds(latestMs),
        unreadCount = unread,
        avatarInitials = "",
        avatarUrl = "",
        avatarTiny = null,
        participants = listOf(me, alice),
        lastRead = Instant.fromEpochMilliseconds(0),
        avatarModel = ConversationAvatarModel(
            type = ConversationAvatarModel.Type.Connection,
            odinId = alice,
        ),
        admins = emptySet(),
        conversationState = ConversationState.Active,
        isGroup = false,
    )

    private fun message(
        author: OdinId,
        userDateMs: Long,
        content: String = "hi",
        isEdited: Boolean = false,
        isStatusMessage: Boolean = false,
        isDeleted: Boolean = false,
    ) = MessageUiModel(
        id = Uuid.random(),
        globalTransitId = null,
        fileId = Uuid.random(),
        conversationId = convoId,
        content = content,
        userDate = Instant.fromEpochMilliseconds(userDateMs),
        modified = null,
        created = Instant.fromEpochMilliseconds(userDateMs),
        originalAuthor = author,
        sender = author,
        displayName = author.domainName,
        localReadTimestamp = null as UnixTimeUtc?,
        isEdited = isEdited,
        isDeleted = isDeleted,
        isPendingSend = false,
        isStatusMessage = isStatusMessage,
        versionTag = Uuid.NIL,
        messageAppData = MessageAppData(),
        reactionPreview = null,
        previewThumbnail = null,
        payloads = null,
        keyHeader = KeyHeader.empty(),
        hasMore = false,
    )

    @Test
    fun peerMessage_bumpsUnreadAndUpdatesPreview() {
        val items = listOf(convo(unread = 0))
        val msg = message(author = alice, userDateMs = 1_000L, content = "hello")

        val updated = applyIncomingMessageBump(
            items = items,
            targetConversationId = convoId,
            m = msg,
            sqlUserDate = Instant.fromEpochMilliseconds(1_000L),
            activeDomain = me,
        )

        assertNotNull(updated)
        val convo = updated.first()
        assertEquals(1, convo.unreadCount)
        assertEquals("hello", convo.lastMessage)
        assertEquals(1_000L, convo.latestMessageTimestamp.toEpochMilliseconds())
        assertEquals(false, convo.lastMessageIsFromActiveUser)
    }

    /**
     * THE regression guard for the bug we just fixed. Three peer messages
     * in sequence (one [applyIncomingMessageBump] call per message,
     * threading the returned list into the next call) must accumulate
     * unread count to 3. The pre-fix code went 0 → 1 → 1 → 1 because
     * `updateConversation` silently dropped each freshly-computed
     * `unreadCount`.
     */
    @Test
    fun sequentialPeerMessages_accumulateUnread() {
        var items: List<ConversationUiModel> = listOf(convo(unread = 0))
        for (i in 1..3) {
            val msg = message(author = alice, userDateMs = i * 1_000L, content = "msg-$i")
            val next = applyIncomingMessageBump(
                items = items,
                targetConversationId = convoId,
                m = msg,
                sqlUserDate = Instant.fromEpochMilliseconds(i * 1_000L),
                activeDomain = me,
            )
            assertNotNull(next, "iteration $i should produce a list change")
            items = next
        }
        assertEquals(3, items.first().unreadCount)
    }

    @Test
    fun selfMessage_doesNotBumpUnread_butStillUpdatesPreview() {
        val items = listOf(convo(unread = 0))
        val msg = message(author = me, userDateMs = 1_000L, content = "my own message")

        val updated = applyIncomingMessageBump(
            items = items,
            targetConversationId = convoId,
            m = msg,
            sqlUserDate = Instant.fromEpochMilliseconds(1_000L),
            activeDomain = me,
        )

        assertNotNull(updated)
        val convo = updated.first()
        assertEquals(0, convo.unreadCount)
        assertEquals("my own message", convo.lastMessage)
        assertEquals(true, convo.lastMessageIsFromActiveUser)
    }

    @Test
    fun editedPeerMessage_doesNotBumpUnread() {
        val items = listOf(convo(unread = 5))
        val msg = message(author = alice, userDateMs = 1_000L, isEdited = true)

        val updated = applyIncomingMessageBump(
            items = items,
            targetConversationId = convoId,
            m = msg,
            sqlUserDate = Instant.fromEpochMilliseconds(1_000L),
            activeDomain = me,
        )

        assertNotNull(updated)
        assertEquals(5, updated.first().unreadCount)
    }

    /**
     * A status message never bumps unread — and since #1153 it doesn't touch
     * the row at all (no preview, no sort-key advance), because the cold-load
     * enrichment path can't surface one either. Full parity coverage lives in
     * [StatusMessagePreviewParityTest].
     */
    @Test
    fun statusMessage_doesNotBumpUnread() {
        val items = listOf(convo(unread = 5))
        val msg = message(author = alice, userDateMs = 1_000L, isStatusMessage = true)

        val updated = applyIncomingMessageBump(
            items = items,
            targetConversationId = convoId,
            m = msg,
            sqlUserDate = Instant.fromEpochMilliseconds(1_000L),
            activeDomain = me,
        )

        assertNull(updated)
        assertEquals(5, items.first().unreadCount)
    }

    @Test
    fun olderMessage_returnsNull_noChange() {
        val items = listOf(convo(latestMs = 5_000L, unread = 2))
        val msg = message(author = alice, userDateMs = 1_000L)

        val updated = applyIncomingMessageBump(
            items = items,
            targetConversationId = convoId,
            m = msg,
            sqlUserDate = Instant.fromEpochMilliseconds(1_000L),
            activeDomain = me,
        )

        assertNull(updated)
    }

    /**
     * Regression guard for the reaction-echo bug observed in homebase.log
     * around 18:15:21 (commit b7aa8809 build): when someone reacts to a
     * message, the server pushes the modified message file (with updated
     * `reactionPreview`) over the WS. That re-emit has the SAME
     * `userDate` as the original message (userDate is the authored
     * time — reactions don't change it). Pre-fix: the `<` guard let
     * the re-emit through and bumped unread on every reaction. Post-fix:
     * the `<=` guard rejects re-emits.
     */
    @Test
    fun reactionEcho_sameUserDate_returnsNull_noUnreadBump() {
        // Seed realistically: the original peer message arrival sets the
        // preview (content "hi", sender alice) and unread=1. The reaction
        // echo re-emits the SAME message with the SAME userDate — reactions
        // change reactionPreview, which is not a conversation-level field, so
        // the recomputed preview is identical and the re-emit is a true no-op
        // (returns null, no unread bump).
        val originalUserDateMs = 1_777_000_000_000L
        val arrival = applyIncomingMessageBump(
            items = listOf(convo(unread = 0)),
            targetConversationId = convoId,
            m = message(author = alice, userDateMs = originalUserDateMs, content = "hi"),
            sqlUserDate = Instant.fromEpochMilliseconds(originalUserDateMs),
            activeDomain = me,
        )
        assertNotNull(arrival)
        assertEquals(1, arrival.first().unreadCount)

        val updated = applyIncomingMessageBump(
            items = arrival,
            targetConversationId = convoId,
            m = message(author = alice, userDateMs = originalUserDateMs, content = "hi"),
            sqlUserDate = Instant.fromEpochMilliseconds(originalUserDateMs),
            activeDomain = me,
        )

        assertNull(
            updated,
            "reaction-driven re-emit (same userDate, unchanged preview) must be a no-op",
        )
        assertEquals(1, arrival.first().unreadCount)
    }

    /**
     * THE #1023 regression guard. When the current last message is
     * soft-deleted, the modified file re-emits over the WS with the SAME
     * `userDate` (deletion doesn't change authored time) but `isDeleted =
     * true`. The pre-fix strict `>` guard hit the `<=` branch and
     * early-returned, so the Chats-list preview stayed frozen on the
     * pre-deletion text ("You: where is the event?") while the open thread
     * correctly showed "This message was deleted". The fix refreshes the
     * denormalised preview on a same-`userDate` re-emit — flipping
     * `lastMessageIsDeleted` — without bumping unread or advancing the
     * timestamp.
     */
    @Test
    fun deletionOfLastMessage_sameUserDate_refreshesPreview_withoutBumpingUnread() {
        val userDateMs = 1_777_000_000_000L

        // A peer message arrives and becomes the last message (unread 0→1).
        val arrival = applyIncomingMessageBump(
            items = listOf(convo(unread = 0)),
            targetConversationId = convoId,
            m = message(author = alice, userDateMs = userDateMs, content = "where is the event?"),
            sqlUserDate = Instant.fromEpochMilliseconds(userDateMs),
            activeDomain = me,
        )
        assertNotNull(arrival)
        assertEquals("where is the event?", arrival.first().lastMessage)
        assertEquals(false, arrival.first().lastMessageIsDeleted)
        assertEquals(1, arrival.first().unreadCount)

        // The message is soft-deleted: same userDate, isDeleted = true.
        val afterDelete = applyIncomingMessageBump(
            items = arrival,
            targetConversationId = convoId,
            m = message(author = alice, userDateMs = userDateMs, content = "", isDeleted = true),
            sqlUserDate = Instant.fromEpochMilliseconds(userDateMs),
            activeDomain = me,
        )

        assertNotNull(
            afterDelete,
            "a same-userDate soft-delete re-emit must refresh the list preview",
        )
        val convo = afterDelete.first()
        assertEquals(true, convo.lastMessageIsDeleted, "list preview must reflect the deletion")
        assertEquals(1, convo.unreadCount, "a re-emit must not bump unread")
        assertEquals(
            userDateMs,
            convo.latestMessageTimestamp.toEpochMilliseconds(),
            "a re-emit must not advance the conversation timestamp",
        )
    }

    /**
     * The #900 preview-refresh contract. Editing the latest message must not
     * move its `userDate` (the sender fix keeps the original un-clamped value),
     * so the edit re-emits over the WS with the SAME `userDate` and new
     * content. The `==` branch must refresh the denormalised preview text —
     * without bumping unread or advancing the conversation timestamp.
     */
    @Test
    fun editOfLastMessage_sameUserDate_refreshesPreviewText_withoutBumpingUnread() {
        val userDateMs = 1_777_000_000_000L

        // A peer message arrives and becomes the last message (unread 0→1).
        val arrival = applyIncomingMessageBump(
            items = listOf(convo(unread = 0)),
            targetConversationId = convoId,
            m = message(author = alice, userDateMs = userDateMs, content = "original"),
            sqlUserDate = Instant.fromEpochMilliseconds(userDateMs),
            activeDomain = me,
        )
        assertNotNull(arrival)
        assertEquals("original", arrival.first().lastMessage)
        assertEquals(1, arrival.first().unreadCount)

        // The message is edited: same userDate, new content, isEdited = true.
        val afterEdit = applyIncomingMessageBump(
            items = arrival,
            targetConversationId = convoId,
            m = message(author = alice, userDateMs = userDateMs, content = "edited", isEdited = true),
            sqlUserDate = Instant.fromEpochMilliseconds(userDateMs),
            activeDomain = me,
        )

        assertNotNull(
            afterEdit,
            "a same-userDate edit re-emit must refresh the list preview",
        )
        val convo = afterEdit.first()
        assertEquals("edited", convo.lastMessage, "list preview must show the edited text")
        assertEquals(1, convo.unreadCount, "an edit re-emit must not bump unread")
        assertEquals(
            userDateMs,
            convo.latestMessageTimestamp.toEpochMilliseconds(),
            "an edit re-emit must not advance the conversation timestamp",
        )
    }

    /**
     * Full reaction-echo lifecycle: a peer message arrives (bump 0→1),
     * then several reaction echoes arrive carrying the same userDate
     * (because reactions don't advance userDate). Unread count must
     * stay at 1, not climb to 2/3/4.
     */
    @Test
    fun peerMessageThenReactionEchoes_doesNotMultiplyUnread() {
        var items: List<ConversationUiModel> = listOf(convo(unread = 0))
        val originalUserDateMs = 1_777_000_000_000L

        // Initial peer message arrival.
        val originalMsg = message(author = alice, userDateMs = originalUserDateMs)
        val afterArrival = applyIncomingMessageBump(
            items = items,
            targetConversationId = convoId,
            m = originalMsg,
            sqlUserDate = Instant.fromEpochMilliseconds(originalUserDateMs),
            activeDomain = me,
        )
        assertNotNull(afterArrival)
        items = afterArrival
        assertEquals(1, items.first().unreadCount)

        // Three reaction-driven re-emits (server fan-out for one reaction).
        // Each carries the same userDate. None should bump.
        for (i in 1..3) {
            val echo = message(author = alice, userDateMs = originalUserDateMs)
            val result = applyIncomingMessageBump(
                items = items,
                targetConversationId = convoId,
                m = echo,
                sqlUserDate = Instant.fromEpochMilliseconds(originalUserDateMs),
                activeDomain = me,
            )
            assertNull(result, "echo #$i must not produce a list change")
        }

        assertEquals(1, items.first().unreadCount, "unread must stay at 1 after reaction echoes")
    }

    @Test
    fun messageForUnknownConversation_returnsNull() {
        val items = listOf(convo())
        val msg = message(author = alice, userDateMs = 1_000L)
        val unknownConvoId = Uuid.parse("99999999-9999-9999-9999-999999999999")

        val updated = applyIncomingMessageBump(
            items = items,
            targetConversationId = unknownConvoId,
            m = msg,
            sqlUserDate = Instant.fromEpochMilliseconds(1_000L),
            activeDomain = me,
        )

        assertNull(updated)
    }

    /**
     * Mirrors the in-memory write loop inside
     * [id.homebase.chat.services.convo.ConversationStream.enrichUnreadLocked]
     * (commonMain). Kept inline so the recount seam can be exercised
     * without ConversationStream's DB + DI graph. If that loop changes,
     * change this too — the two are deliberately coupled.
     */
    private fun simulateEnrichUnreadOverwrite(
        items: List<ConversationUiModel>,
        unreadMap: Map<Uuid, Int>,
    ): List<ConversationUiModel> = items.map { convo ->
        val newCount = unreadMap[convo.id] ?: 0
        if (newCount != convo.unreadCount) convo.copy(unreadCount = newCount) else convo
    }

    /**
     * Characterization test for the **H2** asymmetric-badge path (the Sam vs.
     * Frodo bug captured in plan `snazzy-frolicking-crown`). When a fresh
     * incoming message bumps the in-memory unread count BUT the
     * `selectAllUnreadCount` SQL filter excludes that conversation's row
     * entirely (e.g. `originalAuthor IS NULL`, or `fileState=0`), the
     * conversation is absent from `unreadMap` and the enrich loop overrides
     * the in-memory bump back to 0 — silently losing the badge.
     *
     * This documents the seam as it stands today: present row keeps its
     * bump, absent row gets reset. The fix (once H2 is confirmed by Phase B
     * logs) will likely make `applyIncomingMessageBump` skip increments
     * whose SQL counterpart would be excluded, at which point this test
     * should be updated to assert the corrected behavior.
     */
    @Test
    fun recount_resets_unreadCount_to_zero_when_convo_absent_from_unreadMap() {
        // Two conversations: "sam" (the asymmetric victim) + "frodo" (the
        // working one). Both start at 0 unread; both receive a fresh peer
        // message via applyIncomingMessageBump.
        val samId = convoId
        val frodoId = otherConvoId
        var items: List<ConversationUiModel> = listOf(
            convo(id = samId, unread = 0),
            convo(id = frodoId, unread = 0),
        )

        for ((cid, dateMs) in listOf(samId to 1_000L, frodoId to 1_000L)) {
            // applyIncomingMessageBump matches by `targetConversationId`, not by
            // `m.conversationId`, so we don't need to vary the message field.
            val msg = message(author = alice, userDateMs = dateMs)
            val next = applyIncomingMessageBump(
                items = items,
                targetConversationId = cid,
                m = msg,
                sqlUserDate = Instant.fromEpochMilliseconds(dateMs),
                activeDomain = me,
            )
            assertNotNull(next, "bump for convo=$cid must produce a list change")
            items = next
        }
        // After the two bumps, both convos show unread=1 in memory.
        assertEquals(1, items.first { it.id == samId }.unreadCount)
        assertEquals(1, items.first { it.id == frodoId }.unreadCount)

        // Simulate the SQL recount: Sam's row is excluded entirely (e.g.
        // originalAuthor IS NULL), so the map only contains Frodo.
        val unreadMap = mapOf(frodoId to 1)
        val afterRecount = simulateEnrichUnreadOverwrite(items, unreadMap)

        // Sam silently loses the badge; Frodo keeps it.
        assertEquals(
            0,
            afterRecount.first { it.id == samId }.unreadCount,
            "absent-from-map convo gets reset to 0 — this is the asymmetric-badge mechanism",
        )
        assertEquals(1, afterRecount.first { it.id == frodoId }.unreadCount)
    }

    /**
     * Characterization test for the **H1** asymmetric-badge path. Same
     * symptom as the H2 test above, but here the SQL row for Sam IS in
     * the recount result — it just comes back with `unreadCount = 0`
     * because the stored `lastReadTime` is already ≥ the new message's
     * `userDate` (saturated lastRead, e.g. from a peer-device echo or a
     * markAllAsRead against a future-stamped status-message row).
     *
     * Note the in-memory `latestMessageTimestamp` still advances — this is
     * what makes the bug user-visible: the conversation row shows the
     * correct latest-message preview, but no badge.
     */
    @Test
    fun recount_resets_unreadCount_to_zero_when_lastRead_saturates_past_new_message() {
        val samId = convoId
        val frodoId = otherConvoId
        var items: List<ConversationUiModel> = listOf(
            convo(id = samId, unread = 0),
            convo(id = frodoId, unread = 0),
        )

        for ((cid, dateMs) in listOf(samId to 1_000L, frodoId to 1_000L)) {
            // applyIncomingMessageBump matches by `targetConversationId`, not by
            // `m.conversationId`, so we don't need to vary the message field.
            val msg = message(author = alice, userDateMs = dateMs)
            items = applyIncomingMessageBump(
                items = items,
                targetConversationId = cid,
                m = msg,
                sqlUserDate = Instant.fromEpochMilliseconds(dateMs),
                activeDomain = me,
            ) ?: items
        }
        assertEquals(1, items.first { it.id == samId }.unreadCount)
        assertEquals(1_000L, items.first { it.id == samId }.latestMessageTimestamp.toEpochMilliseconds())

        // SQL recount: Sam's row IS in the map but with count 0 (lastReadTime
        // saturated past the message). Frodo's row reports 1 unread.
        val unreadMap = mapOf(samId to 0, frodoId to 1)
        val afterRecount = simulateEnrichUnreadOverwrite(items, unreadMap)

        val samAfter = afterRecount.first { it.id == samId }
        assertEquals(0, samAfter.unreadCount, "saturated lastRead drops Sam's badge")
        // Preview-side latestMessageTimestamp survived the recount — this is
        // why the user reports "no badge but latest-message preview is correct".
        assertEquals(1_000L, samAfter.latestMessageTimestamp.toEpochMilliseconds())
        assertEquals(1, afterRecount.first { it.id == frodoId }.unreadCount)
    }

    @Test
    fun bump_doesNotTouchOtherConversations() {
        val target = convo(id = convoId, unread = 0)
        val other = convo(id = otherConvoId, unread = 7)
        val items = listOf(target, other)
        val msg = message(author = alice, userDateMs = 1_000L)

        val updated = applyIncomingMessageBump(
            items = items,
            targetConversationId = convoId,
            m = msg,
            sqlUserDate = Instant.fromEpochMilliseconds(1_000L),
            activeDomain = me,
        )

        assertNotNull(updated)
        // The other conversation must be the same instance back — no
        // unnecessary copies, no field changes.
        assertSame(other, updated.first { it.id == otherConvoId })
        assertEquals(7, updated.first { it.id == otherConvoId }.unreadCount)
        assertEquals(1, updated.first { it.id == convoId }.unreadCount)
    }

    // region shouldRunUnreadRecountOnStopped — gate-predicate tests
    //
    // Locks down the decision that the BackendEvent.DriveEvent.Stopped
    // handler makes about whether to run a fresh unread recount. The
    // gate originally was `hasDirtyUnread()` alone; that misses message
    // rows written by DriveSync's QueryBatch path (silent, no
    // BatchReceived emitted), which is what produced the asymmetric-
    // badge bug captured in homebase.log 2026-06-01 Session 5.

    @Test
    fun recountGate_dirtyBitSet_returnsTrue_evenWhenNotChatDrive() {
        // Dirty wins on its own — OR semantics. A non-chat drive that
        // somehow set the dirty bit (peer-device lastRead echo coming
        // through a future code path, e.g.) still triggers a recount.
        assertEquals(
            true,
            shouldRunUnreadRecountOnStopped(
                isChatDrive = false,
                totalCount = 0,
                hasDirtyUnread = true,
            ),
        )
    }

    @Test
    fun recountGate_chatDriveWithRecords_returnsTrue() {
        // THE FIX. The asymmetric-badge bug: BG sync's QueryBatch writes
        // message rows, emits Stopped(totalCount > 0), but never sets
        // the dirty bit. Pre-fix this returned false and the badge
        // stayed stale. The widened gate catches it.
        assertEquals(
            true,
            shouldRunUnreadRecountOnStopped(
                isChatDrive = true,
                totalCount = 8,
                hasDirtyUnread = false,
            ),
        )
    }

    @Test
    fun recountGate_chatDriveAbortedWithPartialRows_returnsTrue() {
        // BackendEvent.DriveResult kdoc warns: "earlier batches' DB
        // writes commit before any later batch can fail." Aborted
        // syncs with totalCount > 0 still represent real rows in DB
        // that affect the count, so we deliberately don't gate on
        // result == Completed. This test pins that decision.
        assertEquals(
            true,
            shouldRunUnreadRecountOnStopped(
                isChatDrive = true,
                totalCount = 3,
                hasDirtyUnread = false,
            ),
        )
    }

    @Test
    fun recountGate_chatDriveWithZeroRecords_returnsFalse() {
        // Nothing landed in the DB this round, nothing to recount.
        // Skips the ~150 ms covering-index scan in the no-op case.
        assertEquals(
            false,
            shouldRunUnreadRecountOnStopped(
                isChatDrive = true,
                totalCount = 0,
                hasDirtyUnread = false,
            ),
        )
    }

    @Test
    fun recountGate_nonChatDriveWithRecords_returnsFalse() {
        // Contacts / Vault / Moments drives never carry chat messages;
        // their Stopped events shouldn't trigger a chat-unread recount.
        assertEquals(
            false,
            shouldRunUnreadRecountOnStopped(
                isChatDrive = false,
                totalCount = 25,
                hasDirtyUnread = false,
            ),
        )
    }

    // endregion
}
