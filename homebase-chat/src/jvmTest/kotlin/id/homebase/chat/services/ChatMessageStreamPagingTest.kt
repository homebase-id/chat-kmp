@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.chat.services

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import id.homebase.api.client.auth.ApiCredentials
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.QueryBatchSortField
import id.homebase.api.client.drives.QueryBatchSortOrder
import id.homebase.api.client.drives.query.QueryBatchCursor
import id.homebase.api.common.OdinId
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
 * `ChatMessageStream.mapToMessageData` companion. **If those query parameters
 * ever change in `fetchMessages`, this file must mirror them — otherwise the
 * test stops protecting the production path.**
 *
 * Scope:
 *   - forward cursor traversal (no dups, no gaps, hasMoreRows transitions)
 *   - NewestFirst-by-userDate ordering
 *   - the rowId=0 cursor encoding's behavior at tied userDate boundaries
 *     (locked-in current behavior — see [tiedUserDateBoundary_currentBehavior])
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
     * the post-mapping pass through [ChatMessageStream.mapToMessageData]. Keep
     * this byte-for-byte aligned with that production call.
     */
    private suspend fun fetchPage(
        conversationId: Uuid,
        limit: Int,
        cursor: QueryBatchCursor? = null,
    ): Triple<List<MessageUiModel>, Boolean, QueryBatchCursor> {
        val queryBatch = QueryBatch(testIdentityId)
        val result = queryBatch.queryBatchAsync(
            dbm = dbm,
            driveId = chatDriveId,
            noOfItems = limit,
            cursor = cursor,
            sortOrder = QueryBatchSortOrder.NewestFirst,
            sortField = QueryBatchSortField.UserDate,
            fileSystemType = 0,
            filetypesAnyOf = listOf(ChatProtocol.MessageFileType),
            groupIdAnyOf = listOf(conversationId),
        )
        val mapped = result.records.mapNotNull { header ->
            ChatMessageStream.mapToMessageData(header, credentialsManager)
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
    ): Uuid {
        val fileId = Uuid.random()
        val messageContent =
            """{"message":"$content","deliveryStatus":20,"isEdited":false,"version":1}"""
        val escapedContent = messageContent.replace("\"", "\\\"")

        val jsonHeader = """{
            "fileId": "$fileId",
            "driveId": "$chatDriveId",
            "fileState": "active",
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
    fun tiedUserDateBoundary_currentBehavior() = runTest {
        // Locks in the *current* behavior of the cursor when the boundary
        // straddles rows that share a userDate. The cursor encoding at
        // QueryBatch.kt:280-285 stores `TimeRowCursor(userDate, 0L)` — the rowId
        // is hardcoded to 0 rather than the actual last row's rowId. Combined
        // with the strict `<` comparison at QueryBatch.kt:223, this means: if
        // the limit-th row and the (limit+1)-th row share a userDate, the
        // (limit+1)-th row is silently dropped from the next page.
        //
        // This is a real correctness gap that paging will surface. By asserting
        // the current behavior here, any later change to fix it will trip this
        // test and force the change to be made deliberately. Update this test
        // when the cursor encoding is fixed; do not update it to "make it
        // pass" without thinking about what fetchMessages now returns.
        val conversationId = Uuid.random()
        val tiedUserDate = 1_700_000_000_000L

        val a = seedMessage(conversationId, userDateMs = tiedUserDate)
        val b = seedMessage(conversationId, userDateMs = tiedUserDate)
        val c = seedMessage(conversationId, userDateMs = tiedUserDate)
        val tied = setOf(a, b, c)

        val (page1, hasMore1, cursor1) = fetchPage(conversationId, limit = 2)
        assertEquals(2, page1.size)
        assertTrue(hasMore1, "third row at the tied userDate should report hasMoreRows")
        assertTrue(page1.all { it.id in tied })

        // Continuing with the cursor: the rowId=0 encoding excludes everything
        // at the tied userDate. Page 2 is empty (current behavior).
        val (page2, hasMore2, _) = fetchPage(conversationId, limit = 2, cursor = cursor1)
        assertEquals(
            emptyList(),
            page2.map { it.id },
            "current cursor encoding drops the third tied-userDate row"
        )
        assertFalse(hasMore2)
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
}
