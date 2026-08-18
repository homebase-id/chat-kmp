@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.chat.services

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.query.QueryBatchCursor
import id.homebase.api.client.drives.query.TimeRowCursor
import id.homebase.api.common.SecureByteArray
import id.homebase.api.common.time.UnixTimeUtc
import id.homebase.chat.data.MessageUiModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Pure logic tests for [PaginatedConversationState] — no DB, no IO.
 *
 * The ChatMessageStream-level cursor round-trip behavior (NewestFirst /
 * OldestFirst with real rows) is covered by [ChatMessageStreamPagingTest].
 */
class PaginatedConversationStateTest {

    private fun message(
        userDateMs: Long,
        id: Uuid = Uuid.random(),
        fileId: Uuid = Uuid.random(),
        conversationId: Uuid,
    ): MessageUiModel = MessageUiModel(
        id = id,
        globalTransitId = null,
        fileId = fileId,
        conversationId = conversationId,
        content = "msg-${id.toString().take(4)}",
        userDate = Instant.fromEpochMilliseconds(userDateMs),
        modified = null,
        created = Instant.fromEpochMilliseconds(userDateMs),
        originalAuthor = null,
        sender = null,
        displayName = "Tester",
        messageAppData = MessageAppData(),
        reactionPreview = null,
        previewThumbnail = null,
        payloads = null,
        keyHeader = KeyHeader(
            iv = ByteArray(16),
            aesKey = SecureByteArray(ByteArray(16)),
        ),
        versionTag = Uuid.random(),
        isPendingSend = false,
        hasMore = false,
    )

    private val convoId: Uuid = Uuid.parse("11111111-1111-1111-1111-111111111111")

    @Test
    fun setInitialWindow_sortsAscendingByUserDate() {
        val state = PaginatedConversationState()
        val a = message(userDateMs = 30, conversationId = convoId)
        val b = message(userDateMs = 10, conversationId = convoId)
        val c = message(userDateMs = 20, conversationId = convoId)

        state.setInitialWindow(convoId, listOf(a, b, c))

        val window = state.getWindow(convoId)
        assertNotNull(window)
        assertEquals(listOf(b.id, c.id, a.id), window.messages.map { it.id })
    }

    @Test
    fun hasCachedMessages_reflectsSetInitialWindow() {
        val state = PaginatedConversationState()
        assertFalse(state.hasCachedMessages(convoId))
        state.setInitialWindow(convoId, emptyList())
        assertTrue(state.hasCachedMessages(convoId))
    }

    @Test
    fun reset_dropsAllCachedWindows() {
        val state = PaginatedConversationState()
        val otherConvoId = Uuid.parse("22222222-2222-2222-2222-222222222222")
        state.setInitialWindow(convoId, listOf(message(userDateMs = 10, conversationId = convoId)))
        state.setInitialWindow(otherConvoId, listOf(message(userDateMs = 20, conversationId = otherConvoId)))
        assertTrue(state.hasCachedMessages(convoId))
        assertTrue(state.hasCachedMessages(otherConvoId))

        // Logout: every previous-identity window must be gone.
        state.reset()

        assertFalse(state.hasCachedMessages(convoId))
        assertFalse(state.hasCachedMessages(otherConvoId))
        assertNull(state.getWindow(convoId))
        assertTrue(state.windows.value.isEmpty())
    }

    @Test
    fun setInitialWindow_merge_keepsConcurrentUpsertsAndLetsTheFreshPageWinOnConflicts() {
        // The raced re-read's write-back. Anything upserted into the window while
        // that fetch ran is absent from its results; replacing would drop it.
        val state = PaginatedConversationState()
        val stale = message(userDateMs = 10, conversationId = convoId)
        val upsertedDuringReRead = message(userDateMs = 30, conversationId = convoId)
        state.setInitialWindow(convoId, listOf(stale, upsertedDuringReRead))

        val refreshed = stale.copy(content = "edited")
        val fromReRead = message(userDateMs = 20, conversationId = convoId)
        state.setInitialWindow(
            conversationId = convoId,
            messages = listOf(refreshed, fromReRead),
            olderCursor = QueryBatchCursor(paging = TimeRowCursor(UnixTimeUtc(10), 0L)),
            hasOlderMessages = true,
            merge = true,
        )

        val window = state.getWindow(convoId)!!
        assertEquals(
            listOf(stale.id, fromReRead.id, upsertedDuringReRead.id),
            window.messages.map { it.id },
            "a message the fresh page doesn't carry must survive the merge",
        )
        assertEquals(
            "edited", window.messages.first { it.id == stale.id }.content,
            "the fresh page wins on id conflicts",
        )
        assertTrue(window.hasOlderMessages, "cursors come from the fresh page")
    }

    @Test
    fun setInitialWindow_withoutMerge_replaces() {
        val state = PaginatedConversationState()
        val gone = message(userDateMs = 10, conversationId = convoId)
        state.setInitialWindow(convoId, listOf(gone))

        val fresh = message(userDateMs = 20, conversationId = convoId)
        state.setInitialWindow(convoId, listOf(fresh))

        assertEquals(listOf(fresh.id), state.getWindow(convoId)!!.messages.map { it.id })
    }

    // ---------- initial-load race bookkeeping (#1135) ----------

    @Test
    fun endInitialLoad_reportsWhetherAWriteLandedDuringTheLoad() {
        val state = PaginatedConversationState()

        state.beginInitialLoad(convoId)
        assertFalse(state.endInitialLoad(convoId), "quiet load must not ask for a re-read")

        state.beginInitialLoad(convoId)
        state.markInitialLoadDirty(convoId)
        assertTrue(state.endInitialLoad(convoId), "a write during the load must ask for a re-read")
    }

    @Test
    fun endInitialLoad_clearsTheMarker_soALaterWriteIsNotAttributedToTheFinishedLoad() {
        // The failure path calls endInitialLoad too; if the marker survived, every
        // later sync round would keep flagging the conversation forever.
        val state = PaginatedConversationState()
        state.beginInitialLoad(convoId)
        state.endInitialLoad(convoId)

        state.markInitialLoadDirty(convoId)
        state.markAllInitialLoadsDirty()

        assertFalse(state.endInitialLoad(convoId))
    }

    @Test
    fun markInitialLoadDirty_isScopedToTheConversation() {
        val state = PaginatedConversationState()
        val otherConvoId = Uuid.parse("44444444-4444-4444-4444-444444444444")
        state.beginInitialLoad(convoId)
        state.beginInitialLoad(otherConvoId)

        state.markInitialLoadDirty(otherConvoId)

        assertFalse(state.endInitialLoad(convoId))
        assertTrue(state.endInitialLoad(otherConvoId))
    }

    @Test
    fun markAllInitialLoadsDirty_flagsEveryInFlightLoad() {
        // refreshCachedWindows walks `windows`, which a mid-load conversation is
        // absent from — so the post-sync path has to flag them all.
        val state = PaginatedConversationState()
        val otherConvoId = Uuid.parse("55555555-5555-5555-5555-555555555555")
        state.beginInitialLoad(convoId)
        state.beginInitialLoad(otherConvoId)

        state.markAllInitialLoadsDirty()

        assertTrue(state.endInitialLoad(convoId))
        assertTrue(state.endInitialLoad(otherConvoId))
    }

    @Test
    fun reset_dropsInFlightLoadMarkers() {
        val state = PaginatedConversationState()
        state.beginInitialLoad(convoId)
        state.markInitialLoadDirty(convoId)

        state.reset()

        assertFalse(state.endInitialLoad(convoId), "logout must not leave a re-read pending")
    }

    @Test
    fun prependOlderMessages_keepsAscendingOrder_andDedupesByMessageId() {
        val state = PaginatedConversationState()
        val mid = message(userDateMs = 50, conversationId = convoId)
        state.setInitialWindow(convoId, listOf(mid), hasOlderMessages = true)

        val older1 = message(userDateMs = 30, conversationId = convoId)
        val older2 = message(userDateMs = 40, conversationId = convoId)
        val midDup = mid.copy(content = "should-be-dropped")

        state.prependOlderMessages(
            conversationId = convoId,
            olderMessages = listOf(older1, older2, midDup),
            olderCursor = QueryBatchCursor(),
            hasMore = false,
        )

        val window = state.getWindow(convoId)!!
        assertEquals(
            listOf(older1.id, older2.id, mid.id),
            window.messages.map { it.id },
            "older messages must precede the existing window in ascending order",
        )
        assertEquals("msg-${mid.id.toString().take(4)}", window.messages.last().content,
            "duplicate by id must be dropped, keeping the existing entry")
        assertFalse(window.hasOlderMessages)
        assertFalse(window.isLoadingOlder)
    }

    @Test
    fun appendNewerMessages_keepsAscendingOrder_andDedupesByMessageId() {
        val state = PaginatedConversationState()
        val anchor = message(userDateMs = 50, conversationId = convoId)
        state.setInitialWindow(convoId, listOf(anchor), hasNewerMessages = true)

        val newer1 = message(userDateMs = 60, conversationId = convoId)
        val newer2 = message(userDateMs = 70, conversationId = convoId)
        val anchorDup = anchor.copy(content = "should-be-dropped")

        state.appendNewerMessages(
            conversationId = convoId,
            newerMessages = listOf(newer1, anchorDup, newer2),
            newerCursor = QueryBatchCursor(),
            hasMore = false,
        )

        val window = state.getWindow(convoId)!!
        assertEquals(
            listOf(anchor.id, newer1.id, newer2.id),
            window.messages.map { it.id },
        )
        assertFalse(window.hasNewerMessages)
        assertFalse(window.isLoadingNewer)
    }

    @Test
    fun prependOlderMessages_overflowing_trimsTail_andRecoversNewerCursor() {
        val state = PaginatedConversationState()
        // Existing window: 200 messages (userDate 800..999).
        val existing = (800..999).map { message(userDateMs = it.toLong(), conversationId = convoId) }
        state.setInitialWindow(convoId, existing, hasOlderMessages = true)

        // Older fetch returns 200 messages (userDate 600..799). Total 400 > MAX (300).
        val older = (600..799).map { message(userDateMs = it.toLong(), conversationId = convoId) }
        state.prependOlderMessages(
            conversationId = convoId,
            olderMessages = older,
            olderCursor = QueryBatchCursor(),
            hasMore = true,
        )

        val window = state.getWindow(convoId)!!
        assertEquals(
            PaginatedConversationState.MAX_WINDOW_SIZE, window.messages.size,
            "window must be trimmed to MAX_WINDOW_SIZE after overflowing prepend",
        )
        // After trimTail, the kept slice is the OLDEST 300 (we trimmed newest).
        assertEquals(
            (600..899).map { it.toLong() },
            window.messages.map { it.userDate.toEpochMilliseconds() },
        )
        // Recovered newer cursor lets a future loadNewerMessages re-cover the
        // discarded slice. The cursor must be non-null and hasNewerMessages = true.
        val newerCursor = window.newerCursor
        assertNotNull(newerCursor)
        val newerPaging = newerCursor.paging
        assertNotNull(newerPaging)
        assertEquals(899L, newerPaging.time.milliseconds,
            "newer cursor anchors at the userDate of the new last (newest-still-kept) message")
        assertEquals(0L, newerPaging.row,
            "rowId=0L lets OldestFirst recover tied-userDate rows on the discarded side")
        assertTrue(window.hasNewerMessages)
    }

    @Test
    fun appendNewerMessages_overflowing_trimsHead_andRecoversOlderCursor() {
        val state = PaginatedConversationState()
        val existing = (200..399).map { message(userDateMs = it.toLong(), conversationId = convoId) }
        state.setInitialWindow(convoId, existing, hasNewerMessages = true)

        val newer = (400..599).map { message(userDateMs = it.toLong(), conversationId = convoId) }
        state.appendNewerMessages(
            conversationId = convoId,
            newerMessages = newer,
            newerCursor = QueryBatchCursor(),
            hasMore = true,
        )

        val window = state.getWindow(convoId)!!
        assertEquals(PaginatedConversationState.MAX_WINDOW_SIZE, window.messages.size)
        // After trimHead, the kept slice is the NEWEST 300.
        assertEquals(
            (300..599).map { it.toLong() },
            window.messages.map { it.userDate.toEpochMilliseconds() },
        )
        val olderCursor = window.olderCursor
        assertNotNull(olderCursor)
        val olderPaging = olderCursor.paging
        assertNotNull(olderPaging)
        assertEquals(300L, olderPaging.time.milliseconds,
            "older cursor anchors at the userDate of the new first (oldest-still-kept) message")
        assertEquals(Long.MAX_VALUE, olderPaging.row,
            "rowId=MAX lets NewestFirst recover tied-userDate rows on the discarded side")
        assertTrue(window.hasOlderMessages)
    }

    @Test
    fun upsert_replacesByMessageId_andResorts() {
        val state = PaginatedConversationState()
        val a = message(userDateMs = 10, conversationId = convoId)
        val b = message(userDateMs = 20, conversationId = convoId)
        state.setInitialWindow(convoId, listOf(a, b))

        // Update `a`'s userDate to land between… new entry takes over.
        val aRevised = a.copy(content = "revised", userDate = Instant.fromEpochMilliseconds(15))
        val c = message(userDateMs = 25, conversationId = convoId)
        state.upsert(convoId, listOf(aRevised, c))

        val window = state.getWindow(convoId)!!
        assertEquals(listOf(aRevised.id, b.id, c.id), window.messages.map { it.id })
        assertEquals("revised", window.messages.first { it.id == a.id }.content)
    }

    @Test
    fun upsert_preservesCursorsHasMoreFlagsAndIsLoadingState() {
        // ChatMessageStream.refreshCachedWindows uses upsert (not
        // setInitialWindow) precisely so the user's paging position
        // survives a DriveSync.Stopped refresh. This pins down that
        // contract so a future change can't silently reset cursors,
        // hasOlder/Newer flags, or in-flight isLoading state on every
        // sync.
        val state = PaginatedConversationState()
        val a = message(userDateMs = 10, conversationId = convoId)
        val b = message(userDateMs = 20, conversationId = convoId)
        val olderCursor = QueryBatchCursor(paging = TimeRowCursor(UnixTimeUtc(10), 0L))
        val newerCursor = QueryBatchCursor(paging = TimeRowCursor(UnixTimeUtc(20), Long.MAX_VALUE))
        state.setInitialWindow(
            conversationId = convoId,
            messages = listOf(a, b),
            olderCursor = olderCursor,
            hasOlderMessages = true,
            newerCursor = newerCursor,
            hasNewerMessages = false,
        )
        state.setLoadingOlder(convoId, true)

        val c = message(userDateMs = 30, conversationId = convoId)
        state.upsert(convoId, listOf(c))

        val window = state.getWindow(convoId)!!
        assertEquals(listOf(a.id, b.id, c.id), window.messages.map { it.id })
        assertEquals(olderCursor, window.olderCursor)
        assertEquals(newerCursor, window.newerCursor)
        assertTrue(window.hasOlderMessages)
        assertFalse(window.hasNewerMessages)
        assertTrue(window.isLoadingOlder,
            "refresh must not clear the user's in-flight older-page load")
    }

    @Test
    fun upsert_isNoOpForUnloadedConversation() {
        val state = PaginatedConversationState()
        val msg = message(userDateMs = 10, conversationId = convoId)
        state.upsert(convoId, listOf(msg))
        assertNull(state.getWindow(convoId))
    }

    @Test
    fun removeMessage_filtersAcrossAllWindows() {
        val state = PaginatedConversationState()
        val convoB = Uuid.parse("22222222-2222-2222-2222-222222222222")
        val a = message(userDateMs = 10, conversationId = convoId)
        val b = message(userDateMs = 20, conversationId = convoId)
        val c = message(userDateMs = 30, conversationId = convoB)
        state.setInitialWindow(convoId, listOf(a, b))
        state.setInitialWindow(convoB, listOf(c))

        state.removeMessage(b.id)

        assertEquals(listOf(a.id), state.getWindow(convoId)!!.messages.map { it.id })
        assertEquals(listOf(c.id), state.getWindow(convoB)!!.messages.map { it.id })
    }

    @Test
    fun removeByFileId_filtersOnlyTargetWindow() {
        val state = PaginatedConversationState()
        val convoB = Uuid.parse("33333333-3333-3333-3333-333333333333")
        val sharedFileId = Uuid.random()
        val a = message(userDateMs = 10, conversationId = convoId, fileId = sharedFileId)
        val b = message(userDateMs = 20, conversationId = convoId)
        val c = message(userDateMs = 30, conversationId = convoB, fileId = sharedFileId)
        state.setInitialWindow(convoId, listOf(a, b))
        state.setInitialWindow(convoB, listOf(c))

        state.removeByFileId(convoId, sharedFileId)

        assertEquals(listOf(b.id), state.getWindow(convoId)!!.messages.map { it.id })
        assertEquals(listOf(c.id), state.getWindow(convoB)!!.messages.map { it.id },
            "removeByFileId scoped to the target conversation must not touch other windows")
    }

    @Test
    fun setLoadingFlags_areIdempotent_andScopedToConversation() {
        val state = PaginatedConversationState()
        state.setInitialWindow(convoId, emptyList())
        state.setLoadingOlder(convoId, true)
        state.setLoadingNewer(convoId, true)
        val window = state.getWindow(convoId)!!
        assertTrue(window.isLoadingOlder)
        assertTrue(window.isLoadingNewer)
        state.setLoadingOlder(convoId, false)
        assertFalse(state.getWindow(convoId)!!.isLoadingOlder)
        assertTrue(state.getWindow(convoId)!!.isLoadingNewer)
    }

    @Test
    fun trimRoundTrip_recoveredCursor_canRefetchTheTrimmedSlice_withoutDups() {
        // When trimTail drops a newer slice and we later loadNewerMessages,
        // the recovered cursor must reach back to the dropped userDates.
        // We can't run a real fetch here (no DB), but we can confirm that
        // the cursor's `time` is the boundary we expect, so a later
        // `appendNewerMessages` with the right olderCursor + hasMore=true
        // would refill the slice.
        val state = PaginatedConversationState()
        val existing = (800..999).map { message(userDateMs = it.toLong(), conversationId = convoId) }
        val older = (600..799).map { message(userDateMs = it.toLong(), conversationId = convoId) }

        state.setInitialWindow(convoId, existing, hasOlderMessages = true)
        state.prependOlderMessages(convoId, older, QueryBatchCursor(), hasMore = false)

        val recoveredCursor = state.getWindow(convoId)!!.newerCursor
        assertNotNull(recoveredCursor)

        // Simulate the user scrolling back down. A loadNewerMessages call
        // would fetch the dropped messages 900..999, which we synthesize:
        val refilled = (900..999).map { message(userDateMs = it.toLong(), conversationId = convoId) }
        state.appendNewerMessages(convoId, refilled, QueryBatchCursor(), hasMore = false)

        val window = state.getWindow(convoId)!!
        // Should have re-trimmed: head gets trimmed because we're back over MAX.
        assertEquals(PaginatedConversationState.MAX_WINDOW_SIZE, window.messages.size)
        // Each id appears at most once.
        assertEquals(
            window.messages.size, window.messages.distinctBy { it.id }.size,
            "refilling the trimmed slice via the recovered cursor must not duplicate rows",
        )
    }

    // ---------- windowed-sync server-backfill flags (#1223) ----------

    @Test
    fun serverFlags_defaultFalseAndSettersUpdateThem() {
        val state = PaginatedConversationState()
        state.setInitialWindow(convoId, listOf(message(userDateMs = 10, conversationId = convoId)))

        val initial = state.getWindow(convoId)!!
        assertFalse(initial.serverHasMoreOlder)
        assertFalse(initial.isLoadingOlderFromServer)

        state.setServerHasMoreOlder(convoId, true)
        state.setLoadingOlderFromServer(convoId, true)
        val updated = state.getWindow(convoId)!!
        assertTrue(updated.serverHasMoreOlder)
        assertTrue(updated.isLoadingOlderFromServer)
    }

    @Test
    fun setInitialWindow_seedsServerHasMoreOlder() {
        val state = PaginatedConversationState()
        state.setInitialWindow(
            convoId,
            listOf(message(userDateMs = 10, conversationId = convoId)),
            serverHasMoreOlder = true,
        )
        assertTrue(state.getWindow(convoId)!!.serverHasMoreOlder)
    }

    @Test
    fun prependOlderMessages_clearsServerLoadingFlag() {
        val state = PaginatedConversationState()
        state.setInitialWindow(
            convoId,
            listOf(message(userDateMs = 100, conversationId = convoId)),
            serverHasMoreOlder = true,
        )
        state.setLoadingOlderFromServer(convoId, true)

        state.prependOlderMessages(
            convoId,
            listOf(message(userDateMs = 50, conversationId = convoId)),
            QueryBatchCursor(),
            hasMore = false,
        )

        val window = state.getWindow(convoId)!!
        assertFalse(window.isLoadingOlderFromServer, "The local re-read is the terminal step of the server path")
        assertTrue(window.serverHasMoreOlder, "Completeness is decided by the server response, not the re-read")
    }
}
