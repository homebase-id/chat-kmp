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
        isDeleted = false,
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

        assertNotNull(updated)
        assertEquals(5, updated.first().unreadCount)
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
        // Convo's latestMessageTimestamp was set to T by the original
        // peer message arrival. The reaction echo carries the same T.
        val originalUserDateMs = 1_777_000_000_000L
        val items = listOf(convo(latestMs = originalUserDateMs, unread = 1))
        val msg = message(author = alice, userDateMs = originalUserDateMs)

        val updated = applyIncomingMessageBump(
            items = items,
            targetConversationId = convoId,
            m = msg,
            sqlUserDate = Instant.fromEpochMilliseconds(originalUserDateMs),
            activeDomain = me,
        )

        assertNull(
            updated,
            "reaction-driven re-emit (same userDate as conversation's latestMessageTimestamp) " +
                "must not bump unread",
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
}
