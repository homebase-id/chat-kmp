package id.homebase.chat.conversationlist

import id.homebase.api.client.KeyHeader
import id.homebase.api.common.OdinId
import id.homebase.chat.data.MessageUiModel
import id.homebase.chat.services.MessageAppData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Covers [resolveScrollToLatestPosition], the bottom-anchor producer behind the
 * scroll-to-bottom FAB's "deep in history" branch.
 *
 * Bug: when the user was paged backwards far enough that the window trimmed
 * (`hasNewerMessages == true`), tapping the FAB reloaded the latest page but
 * never scrolled — the listState stayed parked at its stale history index and
 * the user landed mid-window. The fix has the VM mark the reload and resolve a
 * `triggerScroll` bottom anchor on the next emission; these tests lock that the
 * anchor points at the newest message (and that it degrades to null when there
 * is nothing to land on).
 */
class ScrollToLatestResolverTest {

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

    @Test
    fun deepHistoryReload_landsOnNewestMessage() {
        // The realistic model list the VM produces right after a ScrollToLatest
        // reload: a LoadingOlder spinner (older pages still exist), a date
        // Section, then the freshly loaded newest messages. Crucially there is
        // NO trailing LoadingNewer row, because the reload set
        // hasNewerMessages = false — so the last row IS the latest message.
        val newest = message()
        val models = listOf<MessageListContentModel>(
            MessageListContentModel.LoadingOlder,
            MessageListContentModel.Section(date = kotlinx.datetime.LocalDate(2026, 1, 1)),
            message(),
            message(),
            newest,
        )

        val pos = resolveScrollToLatestPosition(models)

        // Pre-fix the ScrollToLatest path produced no scroll position at all
        // (null), so the user stayed mid-window. It must now point at the bottom.
        assertNotNull(pos)
        assertEquals(models.lastIndex, pos.firstVisibleItemIndex)
        assertEquals(newest.message.id, (models[pos.firstVisibleItemIndex] as MessageListContentModel.Message).message.id)
        assertTrue(pos.triggerScroll)
    }

    @Test
    fun trailingNonMessageRow_resolvesToLastMessageNotTheTrailingRow() {
        // Defensive: even if a non-message row ever sits after the last message,
        // the anchor must be the last MESSAGE, never the trailing row.
        val newest = message()
        val models = listOf<MessageListContentModel>(
            MessageListContentModel.Header,
            newest,
            MessageListContentModel.LoadingNewer,
        )

        val pos = resolveScrollToLatestPosition(models)

        assertNotNull(pos)
        assertEquals(1, pos.firstVisibleItemIndex)
        assertEquals(newest.message.id, (models[pos.firstVisibleItemIndex] as MessageListContentModel.Message).message.id)
        assertTrue(pos.triggerScroll)
    }

    @Test
    fun noMessageRows_returnsNull() {
        val models = listOf<MessageListContentModel>(
            MessageListContentModel.Header,
            MessageListContentModel.UnreadSeparator,
        )
        assertNull(resolveScrollToLatestPosition(models))
    }

    @Test
    fun emptyList_returnsNull() {
        assertNull(resolveScrollToLatestPosition(emptyList()))
    }
}
