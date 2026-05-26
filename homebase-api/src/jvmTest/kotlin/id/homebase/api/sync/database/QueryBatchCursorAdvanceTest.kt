package id.homebase.api.sync.database

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import id.homebase.api.client.drives.QueryBatchSortField
import id.homebase.api.client.drives.QueryBatchSortOrder
import kotlinx.coroutines.runBlocking
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * Reproduces the "infinity spinner on Patrick Kitchell" cursor-advancement bug
 * that the post-PR-A/B/C on-device log surfaced.
 *
 * **Symptom**: opening a conversation whose saved scroll position is mid-history
 * triggers `loadNewer`, which fetches 75 records the window already has,
 * windowSize stays the same, but `hasMore=true`. UI shows an "infinity" loading
 * indicator at the bottom and re-fires `loadNewer` on every nearBottom event.
 *
 * **Root cause** (visible at `QueryBatch.kt:302-308`): for `sortField=UserDate`,
 * after returning N rows the cursor is rebuilt as:
 *
 *     TimeRowCursor(UnixTimeUtc(header.fileMetadata.appData.userDate ?: 0L), 0L)
 *
 * — the `row` component is hardcoded to `0L` instead of the *last returned
 * row's actual `rowId`*. The next fetch's WHERE clause then becomes
 *
 *     (userDate, rowId) > (lastUserDate, 0L)
 *
 * which under SQLite's tuple comparison matches *every* row at `lastUserDate`
 * with `rowId > 0` — i.e. all of them. When multiple messages share the same
 * userDate (e.g. the "no version" branch in `ChatMessageStream.mapToMessageData`
 * that falls back to `authorSpecificDate` — visible repeatedly in the
 * Patrick-conversation log lines), the next batch re-returns the same rows
 * the previous batch did.
 *
 * **The test below seeds 10 messages all sharing the same userDate**, fetches
 * the first 3 with `sortField=UserDate sortOrder=OldestFirst`, then fetches
 * the next 3 with the returned cursor. With the bug present, the second batch
 * overlaps the first (same rows). After the cursor fix (carry through the
 * last-returned `rowId` instead of `0L`), the two batches will be disjoint.
 *
 * **Empirically verified bug** — un-ignoring and running this test against
 * the current code fails as expected, with `overlap` containing all three
 * fileIds the first batch returned (i.e. complete re-fetch of the same set).
 *
 * Marked `@Ignore` so this PR keeps CI green — flip it off once the cursor
 * fix lands and this assertion will hold.
 */
class QueryBatchCursorAdvanceTest {

    @Test
    @Ignore  // currently FAILS — pins the cursor-advancement bug; remove @Ignore after the fix
    fun queryBatchAsync_oldestFirstUserDate_cursorAdvancesPastRowsAtSameUserDate() {
        runBlocking {
            val dbm = DatabaseManager({ JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY) })

            val identityId = Uuid.fromLongs(0L, 1L)
            val driveId = Uuid.fromLongs(0L, 2L)
            val groupId = Uuid.fromLongs(0L, 3L)

            // 10 messages, all sharing userDate = 1_700_000_000_000 — the exact
            // collision pattern the on-device log shows for Patrick's chat
            // (many "no version" rows falling back to the same
            // `authorSpecificDate`). The cursor bug only surfaces when the
            // tail records share a userDate; if every row has a distinct
            // userDate, the (userDate > lastUDate) part of the tuple comparator
            // happens to advance the boundary correctly.
            val collidingUserDate = 1_700_000_000_000L
            val totalRows = 10
            val seededFileIds = (1..totalRows).map { i ->
                val fileId = Uuid.fromLongs(0L, 100L + i)
                seedChatMessage(
                    dbm = dbm,
                    identityId = identityId,
                    driveId = driveId,
                    groupId = groupId,
                    fileId = fileId,
                    uniqueId = Uuid.fromLongs(0L, 200L + i),
                    userDate = collidingUserDate,
                )
                fileId
            }

            val qb = QueryBatch(identityId)
            val pageSize = 3
            val firstBatch = qb.queryBatchAsync(
                dbm = dbm,
                driveId = driveId,
                noOfItems = pageSize,
                cursor = null,
                sortOrder = QueryBatchSortOrder.OldestFirst,
                sortField = QueryBatchSortField.UserDate,
                fileSystemType = 0,
                filetypesAnyOf = listOf(CHAT_MESSAGE_FILE_TYPE),
                groupIdAnyOf = listOf(groupId),
            )
            assertEquals(
                pageSize, firstBatch.records.size,
                "first batch should fill the page (seeded=${seededFileIds.size}, page=$pageSize)",
            )
            assertTrue(
                firstBatch.hasMoreRows,
                "with 10 colliding rows and page=3, more rows must remain after the first batch",
            )

            val cursorPaging = assertNotNull(
                firstBatch.cursor.paging,
                "cursor.paging must be populated after a non-empty fetch",
            )
            assertEquals(
                collidingUserDate, cursorPaging.time.milliseconds,
                "cursor.time should advance to the last-returned record's userDate",
            )

            val secondBatch = qb.queryBatchAsync(
                dbm = dbm,
                driveId = driveId,
                noOfItems = pageSize,
                cursor = firstBatch.cursor,
                sortOrder = QueryBatchSortOrder.OldestFirst,
                sortField = QueryBatchSortField.UserDate,
                fileSystemType = 0,
                filetypesAnyOf = listOf(CHAT_MESSAGE_FILE_TYPE),
                groupIdAnyOf = listOf(groupId),
            )

            val firstIds = firstBatch.records.map { it.fileId }.toSet()
            val secondIds = secondBatch.records.map { it.fileId }.toSet()
            val overlap = firstIds.intersect(secondIds)
            assertTrue(
                overlap.isEmpty(),
                "second fetch with the first's returned cursor must not re-include rows " +
                    "already returned in the first batch — overlap=$overlap " +
                    "(firstIds=$firstIds secondIds=$secondIds)",
            )
        }
    }

    /**
     * Sanity check that the test setup itself can fetch by groupId — pins the
     * insert helper against schema/adapter drift so a failure in the main test
     * unambiguously means "cursor bug" and not "test fixture broken".
     */
    @Test
    fun queryBatchAsync_fetchesAllSeededRows_whenLimitExceedsCount() {
        runBlocking {
            val dbm = DatabaseManager({ JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY) })

            val identityId = Uuid.fromLongs(0L, 1L)
            val driveId = Uuid.fromLongs(0L, 2L)
            val groupId = Uuid.fromLongs(0L, 3L)

            val totalRows = 5
            for (i in 1..totalRows) {
                seedChatMessage(
                    dbm = dbm,
                    identityId = identityId,
                    driveId = driveId,
                    groupId = groupId,
                    fileId = Uuid.fromLongs(0L, 100L + i),
                    uniqueId = Uuid.fromLongs(0L, 200L + i),
                    // distinct userDates here — the bug only manifests with
                    // collisions
                    userDate = 1_700_000_000_000L + i,
                )
            }

            val result = QueryBatch(identityId).queryBatchAsync(
                dbm = dbm,
                driveId = driveId,
                noOfItems = 100,
                cursor = null,
                sortOrder = QueryBatchSortOrder.OldestFirst,
                sortField = QueryBatchSortField.UserDate,
                fileSystemType = 0,
                filetypesAnyOf = listOf(CHAT_MESSAGE_FILE_TYPE),
                groupIdAnyOf = listOf(groupId),
            )
            assertEquals(totalRows, result.records.size)
            assertEquals(false, result.hasMoreRows)
        }
    }

    private companion object {
        // Chat-message file type. Mirror of `ChatProtocol.MessageFileType` in
        // homebase-chat — kept as a literal here so this test stays in
        // homebase-api with no chat-module dependency.
        const val CHAT_MESSAGE_FILE_TYPE = 7878
    }

    /**
     * Seed a chat-shaped row into DriveMainIndex via the typed wrapper. The
     * `jsonHeader` is minimal-but-valid HomebaseFile JSON whose
     * `appData.userDate` matches the SQL `userDate` column — `QueryBatch`'s
     * cursor advancement reads `header.fileMetadata.appData.userDate` from the
     * deserialized header.
     */
    private suspend fun seedChatMessage(
        dbm: DatabaseManager,
        identityId: Uuid,
        driveId: Uuid,
        groupId: Uuid,
        fileId: Uuid,
        uniqueId: Uuid,
        userDate: Long,
    ) {
        val jsonHeader = buildChatMessageJson(
            fileId = fileId,
            driveId = driveId,
            uniqueId = uniqueId,
            groupId = groupId,
            userDate = userDate,
        )
        dbm.driveMainIndex.upsertDriveMainIndex(
            identityId = identityId,
            driveId = driveId,
            fileId = fileId,
            uniqueId = uniqueId,
            globalTransitId = null,
            groupId = groupId,
            senderId = null,
            originalAuthor = null,
            fileType = CHAT_MESSAGE_FILE_TYPE.toLong(),
            dataType = 0L,
            archivalStatus = 1L,
            fileState = 1L,
            historyStatus = 0L,
            userDate = userDate,
            created = userDate,
            modified = userDate,
            fileSystemType = 0L,
            jsonHeader = jsonHeader,
        )
    }

    /**
     * Minimal HomebaseFile JSON sufficient for `QueryBatch.queryBatchAsync` to
     * deserialize the row and read `fileMetadata.appData.userDate`. Mirrors
     * the shape of `ChatMessageStreamMapperTest.buildChatMessageHeader` but
     * without the chat-specific content (we don't render the message here,
     * just paginate over rows).
     */
    private fun buildChatMessageJson(
        fileId: Uuid,
        driveId: Uuid,
        uniqueId: Uuid,
        groupId: Uuid,
        userDate: Long,
    ): String = """
        {
            "fileId": "$fileId",
            "driveId": "$driveId",
            "fileState": "active",
            "fileSystemType": "standard",
            "serverFileIsEncrypted": false,
            "keyHeader": {
                "iv": [0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0],
                "aesKey": {"bytes": [0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0]}
            },
            "fileMetadata": {
                "globalTransitId": null,
                "created": $userDate,
                "updated": $userDate,
                "transitCreated": $userDate,
                "transitUpdated": 0,
                "isEncrypted": false,
                "senderOdinId": "sender.test",
                "originalAuthor": "sender.test",
                "appData": {
                    "uniqueId": "$uniqueId",
                    "tags": null,
                    "fileType": $CHAT_MESSAGE_FILE_TYPE,
                    "dataType": 0,
                    "groupId": "$groupId",
                    "userDate": $userDate,
                    "content": "",
                    "previewThumbnail": null,
                    "archivalStatus": 0
                },
                "localAppData": null,
                "referencedFile": null,
                "reactionPreview": null,
                "versionTag": "00000000-0000-0000-0000-000000000000",
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
                "fileByteCount": 0,
                "originalRecipientCount": 0,
                "transferHistory": null
            },
            "priority": 0,
            "fileByteCount": 0
        }
    """.trimIndent()
}
