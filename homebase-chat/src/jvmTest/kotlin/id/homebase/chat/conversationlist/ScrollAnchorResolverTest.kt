package id.homebase.chat.conversationlist

import id.homebase.api.client.KeyHeader
import id.homebase.api.common.OdinId
import id.homebase.chat.data.MessageUiModel
import id.homebase.chat.services.MessageAppData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Index ↔ anchor-uniqueId mapping used by anchored scroll persistence.
 * See [ScrollAnchorResolver] for the production helpers; the pane uses
 * [resolveAnchorMessageId] when persisting (snapshotFlow → SaveScrollPosition)
 * and the VM uses [resolveAnchorIndex] when restoring (getScrollPosition).
 */
class ScrollAnchorResolverTest {

    private val alice = OdinId("alice.test")
    private val baseTime = Instant.fromEpochMilliseconds(1_700_000_000_000L)

    private fun message(id: Uuid = Uuid.random()): MessageListContentModel.Message =
        MessageListContentModel.Message(
            message = MessageUiModel(
                id = id,
                globalTransitId = null,
                fileId = Uuid.random(),
                conversationId = Uuid.random(),
                content = "test",
                userDate = baseTime,
                modified = null,
                created = baseTime,
                originalAuthor = alice,
                sender = alice,
                displayName = alice.domainName,
                localReadTimestamp = null,
                isDeleted = false,
                isPendingSend = false,
                versionTag = Uuid.NIL,
                messageAppData = MessageAppData(),
                reactionPreview = null,
                previewThumbnail = null,
                payloads = null,
                keyHeader = KeyHeader.empty(),
                hasMore = false,
            ),
        )

    // ---- resolveAnchorMessageId (index → uniqueId, used on persist) ----

    @Test
    fun resolveAnchorMessageId_returnsIdAtExactIndex() {
        val m1 = message()
        val m2 = message()
        val list = listOf<MessageListContentModel>(m1, m2)
        assertEquals(m2.message.id, resolveAnchorMessageId(list, fromIndex = 1))
    }

    @Test
    fun resolveAnchorMessageId_walksForwardPastSeparators() {
        // Date separator at the top of the visible window — anchor should be
        // the FIRST message below it (what the user is actually reading), not
        // the separator and not whatever is above.
        val msg = message()
        val list = listOf<MessageListContentModel>(
            MessageListContentModel.Section(date = kotlinx.datetime.LocalDate(2026, 1, 1)),
            msg,
        )
        assertEquals(msg.message.id, resolveAnchorMessageId(list, fromIndex = 0))
    }

    @Test
    fun resolveAnchorMessageId_returnsNullWhenNoMessageAtOrAfter() {
        val list = listOf<MessageListContentModel>(
            MessageListContentModel.Header,
            MessageListContentModel.UnreadSeparator,
        )
        assertNull(resolveAnchorMessageId(list, fromIndex = 0))
    }

    @Test
    fun resolveAnchorMessageId_returnsNullForNegativeIndex() {
        val list = listOf<MessageListContentModel>(message())
        assertNull(resolveAnchorMessageId(list, fromIndex = -1))
    }

    @Test
    fun resolveAnchorMessageId_returnsNullForOutOfBoundsIndex() {
        val list = listOf<MessageListContentModel>(message())
        assertNull(resolveAnchorMessageId(list, fromIndex = 5))
    }

    @Test
    fun resolveAnchorMessageId_returnsNullForEmptyList() {
        assertNull(resolveAnchorMessageId(emptyList(), fromIndex = 0))
    }

    // ---- resolveAnchorIndex (uniqueId → index, used on restore) ----

    @Test
    fun resolveAnchorIndex_findsMessageInTheMiddle() {
        val target = message()
        val list = listOf<MessageListContentModel>(
            MessageListContentModel.Header,
            message(),
            target,
            message(),
        )
        assertEquals(2, resolveAnchorIndex(list, target.message.id))
    }

    @Test
    fun resolveAnchorIndex_returnsNullForUnknownAnchor() {
        // Saved anchor isn't in the current window — message was deleted or
        // hasn't synced down yet. Caller falls through to "land at bottom."
        val list = listOf<MessageListContentModel>(message(), message())
        assertNull(resolveAnchorIndex(list, anchorMessageId = Uuid.random()))
    }

    @Test
    fun resolveAnchorIndex_returnsNullForEmptyList() {
        assertNull(resolveAnchorIndex(emptyList(), anchorMessageId = Uuid.random()))
    }

    @Test
    fun resolveAnchorIndex_skipsNonMessageRowsWithMatchingPosition() {
        // Defensive: only Message rows are valid anchors. A Section/System row
        // with the same string-id as some message must NOT match.
        val target = message()
        val list = listOf<MessageListContentModel>(
            MessageListContentModel.System(text = "joined", userDate = baseTime, index = 0),
            target,
        )
        assertEquals(1, resolveAnchorIndex(list, target.message.id))
    }
}
