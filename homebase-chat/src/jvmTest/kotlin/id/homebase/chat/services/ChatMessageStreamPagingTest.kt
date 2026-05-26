@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.chat.services

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import id.homebase.api.client.auth.ApiCredentials
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.FileStateFilter
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.QueryBatchSortField
import id.homebase.api.client.drives.QueryBatchSortOrder
import id.homebase.api.client.drives.query.QueryBatchCursor
import id.homebase.api.client.drives.query.TimeRowCursor
import id.homebase.api.common.OdinId
import id.homebase.api.common.time.UnixTimeUtc
import id.homebase.api.common.SecureByteArray
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.sync.database.MainIndexMetaHelpers
import id.homebase.api.sync.database.OdinDatabase
import id.homebase.api.sync.database.QueryBatch
import id.homebase.chat.data.MessageUiModel
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Regression coverage for the cursor-paging contract that
 * [ChatMessageStream.fetchMessages] depends on.
 *
 * Why not call `fetchMessages` directly: its host class needs a real
 * `ContactService` + `DriveFileProvider`, neither of which has a usable test
 * double today. Instead these tests issue the *same* `QueryBatch.queryBatchAsync`
 * call (same `noOfItems`/`cursor`/sortOrder/sortField/fileSystemType/
 * filetypesAnyOf/groupIdAnyOf parameters) that `fetchMessages` issues at
 * `ChatMessageStream.kt:211-222`, then run results through the same
 * `mapToMessageData` decoder (now in `MessageMapper.kt`). **If those query
 * parameters ever change in `fetchMessages`, this file must mirror them —
 * otherwise the test stops protecting the production path.**
 *
 * Scope:
 *   - forward cursor traversal (no dups, no gaps, hasMoreRows transitions)
 *   - NewestFirst-by-userDate ordering
 *   - tied-userDate boundaries (the cursor now carries the boundary rowId,
 *     so pagination is disjoint and exhaustive — see [tiedUserDateBoundary_paginatesDisjointly])
 *   - empty conversation
 *   - groupId isolation
 *   - stability under inserts that land "above" the cursor boundary
 */
class ChatMessageStreamPagingTest {

    private val testIdentityId: Uuid = Uuid.parse("7b1be23b-48bb-4304-bc7b-db5910c09a92")
    private val chatDriveId: Uuid = Uuid.parse("9ff813af-f2d6-1e2f-9b9d-b189e72d1a11")
    private val testDomain: String = "owner.test"

    private lateinit var dbm: DatabaseManager
    private lateinit var credentialsManager: CredentialsManager

    @BeforeTest
    fun setUp() = runTest {
        dbm = DatabaseManager({
            val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            OdinDatabase.Schema.create(driver)
            driver
        })
        credentialsManager = CredentialsManager().apply {
            setActiveCredentials(
                ApiCredentials.create(
                    domain = OdinId(testDomain),
                    clientAccessToken = "test-token",
                    sharedSecret = SecureByteArray(ByteArray(16))
                )
            )
        }
    }

    @AfterTest
    fun tearDown() {
        dbm.close()
    }

    // ---------- helpers ----------

    /**
     * Mirrors the query that [ChatMessageStream.fetchMessages] issues, including
     * the post-mapping pass through [mapToMessageData]. Keep this byte-for-byte
     * aligned with that production call.
     */
    private suspend fun fetchPage(
        conversationId: Uuid,
        limit: Int,
        cursor: QueryBatchCursor? = null,
        sortOrder: QueryBatchSortOrder = QueryBatchSortOrder.NewestFirst,
    ): Triple<List<MessageUiModel>, Boolean, QueryBatchCursor> {
        val queryBatch = QueryBatch(testIdentityId)
        // Per-conversation-type filter — must mirror ChatMessageStream.fetchMessages.
        val fileStateFilter =
            if (conversationId == ChatProtocol.ConversationWithYourselfId) FileStateFilter.Active
            else FileStateFilter.All
        val result = queryBatch.queryBatchAsync(
            dbm = dbm,
            driveId = chatDriveId,
            noOfItems = limit,
            cursor = cursor,
            sortOrder = sortOrder,
            sortField = QueryBatchSortField.UserDate,
            fileSystemType = 0,
            fileState = fileStateFilter,
            filetypesAnyOf = listOf(ChatProtocol.MessageFileType),
            groupIdAnyOf = listOf(conversationId),
        )
        val mapped = result.records.mapNotNull { header ->
            mapToMessageData(header, credentialsManager)
        }
        return Triple(mapped, result.hasMoreRows, result.cursor)
    }

    /**
     * Seed a single chat message row. [userDateMs] is the sort-key value used
     * by `QueryBatch` when `sortField = UserDate`; tests use it as the sole
     * lever for ordering.
     */
    private suspend fun seedMessage(
        conversationId: Uuid,
        userDateMs: Long,
        uniqueId: Uuid = Uuid.random(),
        content: String = "msg-${uniqueId.toString().take(8)}",
        author: String = testDomain,
        fileState: String = "active",
    ): Uuid {
        val fileId = Uuid.random()
        val messageContent =
            """{"message":"$content","deliveryStatus":20,"isEdited":false,"version":1}"""
        val escapedContent = messageContent.replace("\"", "\\\"")

        val jsonHeader = """{
            "fileId": "$fileId",
            "driveId": "$chatDriveId",
            "fileState": "$fileState",
            "fileSystemType": "standard",
            "serverFileIsEncrypted": false,
            "keyHeader": {
                "iv": [0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0],
                "aesKey": {"bytes": [0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0]}
            },
            "fileMetadata": {
                "globalTransitId": "${Uuid.random()}",
                "created": $userDateMs,
                "updated": $userDateMs,
                "transitCreated": $userDateMs,
                "transitUpdated": 0,
                "isEncrypted": false,
                "senderOdinId": "$author",
                "originalAuthor": "$author",
                "appData": {
                    "uniqueId": "$uniqueId",
                    "tags": null,
                    "fileType": ${ChatProtocol.MessageFileType},
                    "dataType": 0,
                    "groupId": "$conversationId",
                    "userDate": $userDateMs,
                    "content": "$escapedContent",
                    "previewThumbnail": null,
                    "archivalStatus": 0
                },
                "localAppData": null,
                "referencedFile": null,
                "reactionPreview": null,
                "versionTag": "${Uuid.random()}",
                "payloads": [],
                "dataSource": null
            },
            "serverMetadata": {
                "accessControlList": {
                    "requiredSecurityGroup": "owner",
                    "circleIdList": null,
                    "odinIdList": null
                },
                "doNotIndex": false,
                "allowDistribution": true,
                "fileSystemType": "standard",
                "fileByteCount": 100,
                "originalRecipientCount": 0,
                "transferHistory": null
            },
            "priority": 300,
            "fileByteCount": 100
        }"""

        val header = OdinSystemSerializer.deserialize<HomebaseFile>(jsonHeader)
        val processor = MainIndexMetaHelpers.HomebaseFileProcessor(dbm)
        val record = processor.convertFileHeaderToDriveMainIndexRecord(
            testIdentityId, chatDriveId, header
        )
        MainIndexMetaHelpers.upsertDriveMainIndex(dbm, record)
        return uniqueId
    }

    // ---------- tests ----------

    /**
     * Note-to-self regression: tombstones have no UX value for self-deletions, so
     * `ChatMessageStream.fetchMessages` filters soft-deleted rows at SQL via
     * `FileStateFilter.Active`. Seeds an interleaved mix of active and deleted
     * messages under `ConversationWithYourselfId` and asserts only the actives
     * come back.
     */
    @Test
    fun fetchMessages_noteToSelf_excludesSoftDeletedRows() = runTest {
        val conversationId = ChatProtocol.ConversationWithYourselfId
        val baseTime = 1_700_000_000_000L

        val activeIds = mutableListOf<Uuid>()
        val deletedIds = mutableListOf<Uuid>()
        // Interleave active and deleted rows so a sort-order bug couldn't
        // accidentally clip the deleted ones off the bottom of the page.
        for (i in 0 until 10) {
            val state = if (i % 2 == 0) "active" else "deleted"
            val id = seedMessage(
                conversationId = conversationId,
                userDateMs = baseTime + i * 1000L,
                fileState = state,
            )
            if (state == "active") activeIds += id else deletedIds += id
        }

        val (page, hasMore, _) = fetchPage(conversationId, limit = 50)

        assertEquals(activeIds.size, page.size, "only active rows must surface")
        assertEquals(activeIds.toSet(), page.map { it.id }.toSet(), "exactly the active ids")
        assertTrue(
            page.map { it.id }.none { it in deletedIds },
            "no deleted id may appear in the page",
        )
        assertFalse(hasMore, "10 actives fit within limit=50; hasMore must be false")
    }

    /**
     * Regular-conversation regression: tombstones DO carry signal ("a peer deleted
     * a message here") and must render as a "Deleted File" placeholder via
     * `MessageMapper`'s deleted-file branch (Signal/WhatsApp convention). Flipping
     * the per-type branch in `ChatMessageStream.fetchMessages` would make this fail.
     */
    @Test
    fun fetchMessages_regularConversation_includesTombstones() = runTest {
        val conversationId = Uuid.random()  // NOT ConversationWithYourselfId
        val baseTime = 1_700_000_000_000L

        val activeId = seedMessage(
            conversationId = conversationId,
            userDateMs = baseTime,
            fileState = "active",
        )
        val deletedId = seedMessage(
            conversationId = conversationId,
            userDateMs = baseTime + 1_000L,
            fileState = "deleted",
        )

        val (page, _, _) = fetchPage(conversationId, limit = 50)

        assertEquals(2, page.size, "regular conversations must surface both active and tombstone rows")
        val byId = page.associateBy { it.id }
        val activeMsg = byId[activeId]
        val deletedMsg = byId[deletedId]
        assertNotNull(activeMsg, "active message must appear")
        assertNotNull(deletedMsg, "tombstone must appear")
        assertFalse(activeMsg.isDeleted, "active row must not be marked deleted")
        assertTrue(deletedMsg.isDeleted, "soft-deleted row must render as tombstone (isDeleted=true)")
        assertEquals(
            "Deleted File", deletedMsg.content,
            "tombstone content must match MessageMapper's deleted-file branch",
        )
    }

    @Test
    fun forwardCursorTraversal_returnsAllMessagesInOrder_withoutDuplicates() = runTest {
        val conversationId = Uuid.random()
        val baseTime = 1_700_000_000_000L

        // Seed 10 messages with strictly increasing userDates (oldest -> newest).
        val seeded = (0 until 10).map { i ->
            seedMessage(conversationId, userDateMs = baseTime + i * 1000L)
        }

        val collected = mutableListOf<MessageUiModel>()
        var cursor: QueryBatchCursor? = null
        var hasMore = true
        var iterations = 0
        while (hasMore && iterations < 10) {
            val (page, more, nextCursor) = fetchPage(conversationId, limit = 4, cursor = cursor)
            collected += page
            hasMore = more
            cursor = nextCursor
            iterations++
        }

        // Each unique uniqueId appears exactly once.
        assertEquals(seeded.size, collected.size, "all seeded rows must surface")
        assertEquals(seeded.toSet(), collected.map { it.id }.toSet(), "no row may be lost")
        assertEquals(collected.size, collected.distinctBy { it.id }.size, "no row may duplicate")

        // NewestFirst by userDate — userDate must monotonically decrease (or stay equal).
        for (i in 1 until collected.size) {
            assertTrue(
                collected[i - 1].userDate >= collected[i].userDate,
                "page $i broke NewestFirst ordering: ${collected[i - 1].userDate} -> ${collected[i].userDate}"
            )
        }

        // Final iteration must report no more rows.
        assertFalse(hasMore, "cursor must terminate when dataset exhausted")
    }

    @Test
    fun fetchOrder_isNewestFirstByUserDate_independentOfInsertOrder() = runTest {
        val conversationId = Uuid.random()
        val baseTime = 1_700_000_000_000L

        // Insert in shuffled order; sort field is userDate, so insertion order
        // must not leak into output.
        val insertOrder = listOf(3, 0, 4, 1, 2)
        val byUserDate = mutableMapOf<Long, Uuid>()
        for (offset in insertOrder) {
            val ud = baseTime + offset * 1000L
            byUserDate[ud] = seedMessage(conversationId, userDateMs = ud)
        }

        val (page, _, _) = fetchPage(conversationId, limit = 10)

        val expectedNewestFirst = byUserDate.entries.sortedByDescending { it.key }.map { it.value }
        assertEquals(expectedNewestFirst, page.map { it.id })
    }

    @Test
    fun tiedUserDateBoundary_paginatesDisjointly() = runTest {
        // Pre-fix this test was named *_currentBehavior and locked in the bug:
        // when the limit-th and (limit+1)-th row shared a userDate, the cursor
        // hardcoded `rowId=0` (rather than the last-returned row's actual
        // rowId), and the next fetch's tuple comparison either silently
        // dropped or duplicated rows at the boundary userDate (the exact
        // failure mode depends on sortOrder — NewestFirst dropped, OldestFirst
        // re-included). The QueryBatch cursor-encoding fix in this same PR
        // carries the boundary rowId through, so pagination is now disjoint
        // and exhaustive across tied userDates.
        //
        // This test pins the fixed behavior: 3 rows at the same userDate,
        // pageSize=2, must yield page1=2 rows + page2=1 row, exhausting the
        // set with no overlap and no drops.
        val conversationId = Uuid.random()
        val tiedUserDate = 1_700_000_000_000L

        val a = seedMessage(conversationId, userDateMs = tiedUserDate)
        val b = seedMessage(conversationId, userDateMs = tiedUserDate)
        val c = seedMessage(conversationId, userDateMs = tiedUserDate)
        val tied = setOf(a, b, c)

        val (page1, hasMore1, cursor1) = fetchPage(conversationId, limit = 2)
        assertEquals(2, page1.size)
        assertTrue(hasMore1, "third row at the tied userDate should report hasMoreRows")
        val page1Ids = page1.map { it.id }.toSet()
        assertTrue(page1Ids.all { it in tied })

        val (page2, hasMore2, _) = fetchPage(conversationId, limit = 2, cursor = cursor1)
        val page2Ids = page2.map { it.id }.toSet()
        assertEquals(
            1, page2Ids.size,
            "page 2 should return the remaining 1 of 3 tied-userDate rows",
        )
        assertTrue(page2Ids.all { it in tied })
        assertTrue(
            page1Ids.intersect(page2Ids).isEmpty(),
            "page 1 and page 2 must be disjoint — overlap=" +
                "${page1Ids.intersect(page2Ids)}",
        )
        assertEquals(
            tied, page1Ids + page2Ids,
            "union of the two pages should cover every tied-userDate row exactly once",
        )
        assertFalse(hasMore2, "after returning the third tied row, dataset must be exhausted")
    }

    @Test
    fun emptyConversation_returnsEmptyResultAndNoMoreRows() = runTest {
        val (page, hasMore, _) = fetchPage(Uuid.random(), limit = 50)
        assertTrue(page.isEmpty())
        assertFalse(hasMore)
    }

    @Test
    fun conversationFilter_isolatesByGroupId() = runTest {
        val convoA = Uuid.random()
        val convoB = Uuid.random()
        val baseTime = 1_700_000_000_000L

        val aIds = (0 until 3).map { seedMessage(convoA, userDateMs = baseTime + it) }.toSet()
        val bIds = (0 until 5).map { seedMessage(convoB, userDateMs = baseTime + it) }.toSet()

        val (pageA, _, _) = fetchPage(convoA, limit = 100)
        val (pageB, _, _) = fetchPage(convoB, limit = 100)

        assertEquals(aIds, pageA.map { it.id }.toSet())
        assertEquals(bIds, pageB.map { it.id }.toSet())
    }

    @Test
    fun limitEqualsRowCount_reportsHasMoreRowsFalse() = runTest {
        val conversationId = Uuid.random()
        val baseTime = 1_700_000_000_000L
        repeat(4) { seedMessage(conversationId, userDateMs = baseTime + it) }

        val (page, hasMore, _) = fetchPage(conversationId, limit = 4)
        assertEquals(4, page.size)
        assertFalse(hasMore, "exact-fit page must not signal more rows")
    }

    @Test
    fun fetchPage_oldestFirst_returnsAscendingOrder() = runTest {
        // ChatMessageStream.loadNewerMessages calls fetchMessages with sortOrder = OldestFirst
        // so the next page (newer than the boundary) arrives oldest-first. This regression
        // protects that direction's ordering.
        val conversationId = Uuid.random()
        val baseTime = 1_700_000_000_000L
        val seeded = (0 until 5).map { seedMessage(conversationId, userDateMs = baseTime + it * 1000L) }

        val (page, _, _) = fetchPage(
            conversationId = conversationId,
            limit = 10,
            sortOrder = QueryBatchSortOrder.OldestFirst,
        )

        assertEquals(seeded, page.map { it.id }, "OldestFirst must return rows in seed order")
        for (i in 1 until page.size) {
            assertTrue(
                page[i - 1].userDate <= page[i].userDate,
                "OldestFirst must yield ascending userDate"
            )
        }
    }

    @Test
    fun fetchPage_oldestFirst_cursorAtBoundary_skipsAlreadySeenRows() = runTest {
        // After loadConversationAroundMessage seeds a window with `target.userDate` as the
        // newer boundary, a follow-up loadNewerMessages must NOT return the boundary row
        // again. The cursor used by ChatMessageStream is `TimeRowCursor(target.userDate, MAX)`
        // — strict `>` comparison excludes the boundary row.
        val conversationId = Uuid.random()
        val baseTime = 1_700_000_000_000L
        val ids = (0 until 4).map { seedMessage(conversationId, userDateMs = baseTime + it * 1000L) }
        val boundaryUserDate = baseTime + 1 * 1000L  // ids[1]
        val expectedNewer = listOf(ids[2], ids[3])

        val cursor = QueryBatchCursor(
            paging = TimeRowCursor(UnixTimeUtc(boundaryUserDate), Long.MAX_VALUE),
        )
        val (page, hasMore, _) = fetchPage(
            conversationId = conversationId,
            limit = 10,
            cursor = cursor,
            sortOrder = QueryBatchSortOrder.OldestFirst,
        )

        assertEquals(expectedNewer, page.map { it.id })
        assertFalse(hasMore)
    }

    @Test
    fun stabilityUnderInsert_newerMessageAfterCursor_doesNotLeakIntoNextPage() = runTest {
        // Cursor is monotonic backward in time (NewestFirst). A row inserted
        // with a userDate *newer* than the cursor's boundary is "above" the
        // cursor — paging must skip it. (The application sees such rows when
        // BatchReceived events fire; that's an orthogonal path, not paging.)
        val conversationId = Uuid.random()
        val baseTime = 1_700_000_000_000L

        // Seed 6 messages: userDates baseTime+0 .. baseTime+5 (newest = +5).
        val initial = (0 until 6).map {
            seedMessage(conversationId, userDateMs = baseTime + it.toLong())
        }

        // Page 1: newest 3.
        val (page1, hasMore1, cursor1) = fetchPage(conversationId, limit = 3)
        assertEquals(3, page1.size)
        assertTrue(hasMore1)

        // Insert a message NEWER than every existing message (well above the
        // cursor's boundary userDate).
        val intruder = seedMessage(conversationId, userDateMs = baseTime + 9_999L)

        // Page 2 with the cursor from page 1.
        val (page2, _, _) = fetchPage(conversationId, limit = 3, cursor = cursor1)

        assertFalse(
            page2.any { it.id == intruder },
            "messages inserted above the cursor must not appear in paged results"
        )

        // The two pages together must cover the original six (cursor encoding
        // may drop a tied-userDate row at the boundary, but here userDates are
        // strictly distinct so no losses are expected).
        val seen = (page1 + page2).map { it.id }.toSet()
        assertEquals(initial.toSet(), seen, "original rows must be reachable across pages")
    }

    // ---------- integration-style tests ----------
    //
    // The tests below seed >3×PAGE_SIZE messages into the DB and drive
    // [PaginatedConversationState] through the same calls
    // [ChatMessageStream] makes — proving that the cursor round-trip across
    // the holder + DB delivers every row exactly once, including from an
    // anchored centered window. We can't construct a real ChatMessageStream
    // here (its ContactService + DriveFileProvider deps have no test
    // double; see the file-level comment), so these helpers replicate the
    // sequencing that ChatMessageStream's loadConversation /
    // loadOlderMessages / loadNewerMessages /
    // loadConversationAroundMessage perform.

    // Tiny page/window sizes so the test exercises trim+recovery without
    // seeding hundreds of rows. With pageSize=10 and maxWindowSize=25, a
    // 35-message conversation forces at least one trim during the older
    // walk, then the recovered newer cursor must walk back to the latest.
    private val testPageSize = 10
    private val testMaxWindowSize = 25
    private val testTotal = 35

    private suspend fun simLoadConversation(
        state: PaginatedConversationState,
        conversationId: Uuid,
    ) {
        val (messages, hasMore, cursor) = fetchPage(
            conversationId = conversationId,
            limit = testPageSize,
            sortOrder = QueryBatchSortOrder.NewestFirst,
        )
        state.setInitialWindow(
            conversationId = conversationId,
            messages = messages,
            olderCursor = cursor,
            hasOlderMessages = hasMore,
        )
    }

    private suspend fun simLoadOlder(
        state: PaginatedConversationState,
        conversationId: Uuid,
    ): Boolean {
        val window = state.getWindow(conversationId) ?: return false
        if (!window.hasOlderMessages) return false
        val (messages, hasMore, cursor) = fetchPage(
            conversationId = conversationId,
            limit = testPageSize,
            cursor = window.olderCursor,
            sortOrder = QueryBatchSortOrder.NewestFirst,
        )
        state.prependOlderMessages(conversationId, messages, cursor, hasMore)
        return true
    }

    private suspend fun simLoadNewer(
        state: PaginatedConversationState,
        conversationId: Uuid,
    ): Boolean {
        val window = state.getWindow(conversationId) ?: return false
        if (!window.hasNewerMessages) return false
        val (messages, hasMore, cursor) = fetchPage(
            conversationId = conversationId,
            limit = testPageSize,
            cursor = window.newerCursor,
            sortOrder = QueryBatchSortOrder.OldestFirst,
        )
        state.appendNewerMessages(conversationId, messages, cursor, hasMore)
        return true
    }

    /**
     * Mirrors [ChatMessageStream.loadConversationAroundMessage]: fetch
     * `pageSize/2` older and newer of [anchorUserDateMs] in their respective
     * sort orders, merge with the anchor row, and seed the window. The
     * production path also calls `getMessage(anchorId)` to inject the
     * anchor row itself (which the strict `<` / `>` cursor on either side
     * skips); we replicate that injection by passing in the seeded
     * [anchorMessage].
     */
    private suspend fun simLoadAround(
        state: PaginatedConversationState,
        conversationId: Uuid,
        anchorUserDateMs: Long,
        anchorMessage: MessageUiModel,
    ) {
        val halfPage = testPageSize / 2
        val olderCursor = QueryBatchCursor(
            paging = TimeRowCursor(UnixTimeUtc(anchorUserDateMs), 0L),
        )
        val newerCursor = QueryBatchCursor(
            paging = TimeRowCursor(UnixTimeUtc(anchorUserDateMs), Long.MAX_VALUE),
        )
        val (olderMessages, olderHasMore, olderCursorOut) = fetchPage(
            conversationId = conversationId,
            limit = halfPage,
            cursor = olderCursor,
            sortOrder = QueryBatchSortOrder.NewestFirst,
        )
        val (newerMessages, newerHasMore, newerCursorOut) = fetchPage(
            conversationId = conversationId,
            limit = halfPage,
            cursor = newerCursor,
            sortOrder = QueryBatchSortOrder.OldestFirst,
        )
        val combined = (olderMessages + listOf(anchorMessage) + newerMessages)
            .distinctBy { it.id }
        state.setInitialWindow(
            conversationId = conversationId,
            messages = combined,
            olderCursor = olderCursorOut,
            hasOlderMessages = olderHasMore,
            newerCursor = newerCursorOut,
            hasNewerMessages = newerHasMore,
        )
    }

    @Test
    fun fullPaging_walksAcrossMoreThanThreePages_withoutGapsOrDups() = runTest {
        // 35 rows ÷ pageSize 10 ⇒ 4 pages. After ~3 prepends the window
        // (25-row cap) must trim its newer end, recovering a newer cursor
        // that lets a follow-up walk back down reach the trimmed slice.
        val conversationId = Uuid.random()
        val baseTime = 1_700_000_000_000L
        val seeded = (0 until testTotal).map {
            seedMessage(conversationId, userDateMs = baseTime + it * 1000L)
        }

        val state = PaginatedConversationState(maxWindowSize = testMaxWindowSize)
        simLoadConversation(state, conversationId)

        val initial = state.getWindow(conversationId)!!
        assertEquals(testPageSize, initial.messages.size)
        assertTrue(initial.hasOlderMessages)
        assertFalse(initial.hasNewerMessages)

        // Walk older to exhaustion, capturing every id we ever see.
        val seenIds = mutableSetOf<Uuid>()
        seenIds += initial.messages.map { it.id }
        var hops = 0
        while (state.getWindow(conversationId)!!.hasOlderMessages && hops < 16) {
            simLoadOlder(state, conversationId)
            seenIds += state.getWindow(conversationId)!!.messages.map { it.id }
            hops++
        }
        assertFalse(state.getWindow(conversationId)!!.hasOlderMessages)

        // After exhausting older, the trimmed newer slice MUST be recoverable
        // via the newer cursor — proving trim+recovery round-trips the rows
        // we trimmed during the older walk.
        assertTrue(
            state.getWindow(conversationId)!!.hasNewerMessages,
            "after a 35-row dataset paged into a 25-row window, the newer cursor must be live",
        )
        var fwdHops = 0
        while (state.getWindow(conversationId)!!.hasNewerMessages && fwdHops < 16) {
            simLoadNewer(state, conversationId)
            seenIds += state.getWindow(conversationId)!!.messages.map { it.id }
            fwdHops++
        }
        assertFalse(state.getWindow(conversationId)!!.hasNewerMessages)

        assertEquals(
            seeded.toSet(), seenIds,
            "every seeded row must have been visible in some window during the walk",
        )
    }

    @Test
    fun anchoredOpen_centersWindowAroundAnchor_andWalksBothDirections_toEnds() = runTest {
        val conversationId = Uuid.random()
        val baseTime = 1_700_000_000_000L
        val seeded = (0 until testTotal).map {
            seedMessage(conversationId, userDateMs = baseTime + it * 1000L)
        }

        // Re-fetch the seeded rows to get full MessageUiModel for the anchor.
        val (allMessages, _, _) = fetchPage(
            conversationId = conversationId,
            limit = testTotal,
            sortOrder = QueryBatchSortOrder.OldestFirst,
        )
        val anchorIdx = testTotal / 2  // index 17
        val anchor = allMessages[anchorIdx]
        val anchorUserDate = baseTime + anchorIdx * 1000L

        val state = PaginatedConversationState(maxWindowSize = testMaxWindowSize)
        simLoadAround(state, conversationId, anchorUserDate, anchor)

        val centered = state.getWindow(conversationId)!!
        // halfPage=5 each side + anchor itself = 11 (or fewer near edges).
        assertTrue(
            centered.messages.size in (testPageSize / 2)..(testPageSize + 1),
            "centered window should hold ~halfPage*2+anchor messages, got ${centered.messages.size}",
        )
        assertTrue(centered.messages.any { it.id == anchor.id }, "anchor row must be in the centered window")
        assertTrue(centered.hasOlderMessages)
        assertTrue(centered.hasNewerMessages)

        // Walk older to exhaustion.
        val seenIds = mutableSetOf<Uuid>()
        seenIds += centered.messages.map { it.id }
        var olderHops = 0
        while (state.getWindow(conversationId)!!.hasOlderMessages && olderHops < 16) {
            simLoadOlder(state, conversationId)
            seenIds += state.getWindow(conversationId)!!.messages.map { it.id }
            olderHops++
        }
        assertFalse(state.getWindow(conversationId)!!.hasOlderMessages)

        // Walk newer to exhaustion.
        var newerHops = 0
        while (state.getWindow(conversationId)!!.hasNewerMessages && newerHops < 16) {
            simLoadNewer(state, conversationId)
            seenIds += state.getWindow(conversationId)!!.messages.map { it.id }
            newerHops++
        }
        assertFalse(state.getWindow(conversationId)!!.hasNewerMessages)

        assertEquals(
            seeded.toSet(), seenIds,
            "anchored open + bidirectional walk must reach every seeded row including the anchor",
        )
    }

    /**
     * Mirrors [ChatMessageStream.refreshCachedWindows] for one conversation:
     * re-fetch the newest page from the DB and upsert it into the window.
     * Production code iterates `paginatedState.windows.value` and applies
     * the same gate per entry; the tests below drive one window at a time.
     *
     * Keep the gate and query parameters in sync with production
     * (ChatMessageStream.kt:refreshCachedWindows). If those drift, this
     * helper stops protecting the production path.
     */
    private suspend fun simRefreshCachedWindows(
        state: PaginatedConversationState,
        conversationId: Uuid,
    ) {
        val window = state.getWindow(conversationId) ?: return
        if (window.hasNewerMessages) return
        val (messages, _, _) = fetchPage(
            conversationId = conversationId,
            limit = testPageSize,
            sortOrder = QueryBatchSortOrder.NewestFirst,
        )
        if (messages.isEmpty()) return
        state.upsert(conversationId, messages)
    }

    @Test
    fun refreshCachedWindows_mergesNewlyLandedMessages_withoutDuplicates() = runTest {
        // Simulates the flight-mode repro: an open conversation window
        // exists, DriveSync silently lands new messages in DriveMainIndex,
        // refreshCachedWindows fires on any Stopped with totalCount > 0
        // (Completed or Aborted-with-partial-data) and must surface the
        // new rows in the in-memory window without duplicating the
        // already-present ones.
        val conversationId = Uuid.random()
        val baseTime = 1_700_000_000_000L
        val initial = (0 until 5).map {
            seedMessage(conversationId, userDateMs = baseTime + it * 1000L)
        }

        val state = PaginatedConversationState(maxWindowSize = testMaxWindowSize)
        simLoadConversation(state, conversationId)
        assertEquals(
            initial.toSet(),
            state.getWindow(conversationId)!!.messages.map { it.id }.toSet(),
            "pre-refresh window must contain only the initially-seeded rows",
        )

        // Two new messages land in the DB (DriveSync analog) while the
        // window is open. The production handler responds to any
        // DriveEvent.Stopped with totalCount > 0 (including Failure with
        // partial data — earlier batches' writes have already committed).
        val arrived1 = seedMessage(conversationId, userDateMs = baseTime + 10_000L)
        val arrived2 = seedMessage(conversationId, userDateMs = baseTime + 11_000L)

        simRefreshCachedWindows(state, conversationId)

        val ids = state.getWindow(conversationId)!!.messages.map { it.id }
        assertTrue(arrived1 in ids, "newly-landed arrived1 must surface in the window")
        assertTrue(arrived2 in ids, "newly-landed arrived2 must surface in the window")
        assertEquals(
            ids.size, ids.distinct().size,
            "upsert must dedupe by message id — no row may appear twice",
        )
        assertTrue(
            initial.all { it in ids },
            "pre-existing messages must remain after refresh",
        )
    }

    @Test
    fun refreshCachedWindows_skipsWindowsPagedDeepIntoHistory() = runTest {
        // When the user has paged into history (hasNewerMessages == true),
        // merging the newest page from the DB into the window would leave
        // a visible gap between the loaded slice and the new tail. The
        // production gate skips these windows; they refresh naturally
        // when the user scrolls back to the latest page via
        // loadNewerMessages. This locks in that gate.
        val conversationId = Uuid.random()
        val baseTime = 1_700_000_000_000L
        val seeded = (0 until testTotal).map {
            seedMessage(conversationId, userDateMs = baseTime + it * 1000L)
        }

        // Re-fetch to materialize the anchor as a MessageUiModel.
        val (allMessages, _, _) = fetchPage(
            conversationId = conversationId,
            limit = testTotal,
            sortOrder = QueryBatchSortOrder.OldestFirst,
        )
        val anchorIdx = testTotal / 2
        val anchor = allMessages[anchorIdx]
        val anchorUserDate = baseTime + anchorIdx * 1000L

        val state = PaginatedConversationState(maxWindowSize = testMaxWindowSize)
        simLoadAround(state, conversationId, anchorUserDate, anchor)
        assertTrue(
            state.getWindow(conversationId)!!.hasNewerMessages,
            "anchored open in the middle must produce hasNewerMessages = true",
        )
        val before = state.getWindow(conversationId)!!.messages.map { it.id }
        assertTrue(seeded.last() !in before, "anchored window must not already include the latest row")

        // DriveSync lands a message newer than anything currently visible.
        val arrived = seedMessage(conversationId, userDateMs = baseTime + (testTotal + 100) * 1000L)

        simRefreshCachedWindows(state, conversationId)

        val after = state.getWindow(conversationId)!!.messages.map { it.id }
        assertEquals(
            before, after,
            "window paged deep into history must not absorb a latest-page refresh",
        )
        assertFalse(
            arrived in after,
            "the newly-arrived latest-page row must not appear until the user pages back to the latest",
        )
    }
}
