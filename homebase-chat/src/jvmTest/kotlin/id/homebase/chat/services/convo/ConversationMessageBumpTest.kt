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
