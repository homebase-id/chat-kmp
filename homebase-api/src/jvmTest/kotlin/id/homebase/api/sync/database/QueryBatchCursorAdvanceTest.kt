package id.homebase.api.sync.database

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import id.homebase.api.client.drives.QueryBatchSortField
import id.homebase.api.client.drives.QueryBatchSortOrder
import id.homebase.api.client.drives.query.QueryBatchCursor
import kotlinx.coroutines.runBlocking
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
 * **The tests below seed messages sharing a userDate** and assert that
 * pagination returns disjoint pages (no overlap) and exhausts the dataset
 * (`hasMore=false` on the last page). The fix carries the last-returned
 * `rowId` through into the next cursor instead of hardcoding `0L`, so the
 * next fetch's WHERE `(time, rowId) > (lastTime, lastRowId)` correctly
 * skips past the boundary row even when multiple rows share `lastTime`.
 *
 * The fix lives at `QueryBatch.kt` (cursor-build after a batch returns).
 * Without it, the on-device "infinity spinner on Patrick Kitchell" is
 * deterministic — every `loadNewer` returns the same rows, `hasMore=true`
 * stays true forever.
 */
class QueryBatchCursorAdvanceTest {

    @Test
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
     * Stronger formulation: 9 rows all at `userDate=0` (the most extreme
     * collision possible — also the value the seeder defaults to when an
     * appData has no userDate), paged 3 at a time. With the cursor bug, every
     * page returns the same first 3 rows. With the fix carrying the last
     * rowId through, the three pages cover all 9 rows disjointly and the
     * third page reports `hasMore=false`.
     *
     * Asserts both invariants:
     *  1. Page N+1's rows do not overlap with page N's rows.
     *  2. The union of all three pages equals the seeded set.
     */
    @Test
    fun queryBatchAsync_oldestFirstUserDate_paginatesDisjointlyWhenAllRowsShareUserDate() {
        runBlocking {
            val dbm = DatabaseManager({ JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY) })

            val identityId = Uuid.fromLongs(0L, 1L)
            val driveId = Uuid.fromLongs(0L, 2L)
            val groupId = Uuid.fromLongs(0L, 3L)

            val totalRows = 9
            val pageSize = 3
            val expectedAllIds = (1..totalRows).map { i ->
                val fileId = Uuid.fromLongs(0L, 100L + i)
                seedChatMessage(
                    dbm = dbm,
                    identityId = identityId,
                    driveId = driveId,
                    groupId = groupId,
                    fileId = fileId,
                    uniqueId = Uuid.fromLongs(0L, 200L + i),
                    userDate = 0L,
                )
                fileId
            }.toSet()

            val qb = QueryBatch(identityId)
            val pages = mutableListOf<Set<Uuid>>()
            var cursor = QueryBatchCursor()
            var hasMore = true
            var safetyCounter = 0
            while (hasMore) {
                // Cap the loop hard so the buggy "cursor never advances"
                // version of this test fails with a clear message instead of
                // looping forever. With the fix in place we expect exactly
                // 3 iterations.
                if (++safetyCounter > 6) {
                    error(
                        "cursor never advanced — paged ${pages.size} times without " +
                            "exhausting the 9-row dataset (pages so far: $pages)"
                    )
                }
                val batch = qb.queryBatchAsync(
                    dbm = dbm,
                    driveId = driveId,
                    noOfItems = pageSize,
                    cursor = cursor,
                    sortOrder = QueryBatchSortOrder.OldestFirst,
                    sortField = QueryBatchSortField.UserDate,
                    fileSystemType = 0,
                    filetypesAnyOf = listOf(CHAT_MESSAGE_FILE_TYPE),
                    groupIdAnyOf = listOf(groupId),
                )
                val pageIds = batch.records.map { it.fileId }.toSet()
                // Overlap pin per-step so the failure points at the offending page.
                for ((idx, prior) in pages.withIndex()) {
                    val overlap = pageIds.intersect(prior)
                    assertTrue(
                        overlap.isEmpty(),
                        "page #${pages.size + 1} overlaps with page #${idx + 1}: " +
                            "overlap=$overlap thisPage=$pageIds priorPage=$prior",
                    )
                }
                pages += pageIds
                cursor = batch.cursor
                hasMore = batch.hasMoreRows
            }

            assertEquals(
                3, pages.size,
                "with 9 rows at the same userDate and pageSize=3, pagination should " +
                    "yield exactly 3 disjoint pages (got ${pages.size}: $pages)",
            )
            val union = pages.fold(emptySet<Uuid>()) { acc, p -> acc + p }
            assertEquals(
                expectedAllIds, union,
                "union of all pages should equal the seeded set — missing=" +
                    "${expectedAllIds - union} extra=${union - expectedAllIds}",
            )
        }
    }

    /**
     * Pins the *userDate-source* cursor bug surfaced by Patrick Kitchell's
     * conversation on prod 1.4.1678. Distinct from the rowId-tuple bug fixed
     * in the same QueryBatch.kt cursor-build block — that one only manifested
     * at tied userDate boundaries. This one fires on *any* row whose
     * `appData.userDate` is null in the JSON header (the chat "no version,
     * not edited" branch: older Web/RN clients didn't always emit
     * `appData.userDate`).
     *
     * **Production projection** at `MainIndexMeta.kt:127-128`:
     *   SQL `userDate` column = `appData.userDate ?: created.milliseconds`
     *
     * **Pre-fix `QueryBatch.kt:316`** wrote the cursor's userDate as:
     *   `UnixTimeUtc(header.fileMetadata.appData.userDate ?: 0L)`
     *
     * For a null-`appData.userDate` row, that was `(0L, lastRowId)`. The next
     * fetch's `WHERE (userDate, rowId) > (0L, lastRowId) ORDER BY userDate ASC`
     * matched *every* row (because `userDate > 0` is true for every real row)
     * and returned the **oldest 75** — already in the window. Window stayed
     * the same size, `hasMore=true`, infinity-spinner on the bottom.
     *
     * **The fix**: use `header.sqlUserDateMs()` so the cursor's userDate
     * matches the SQL column projection exactly.
     *
     * Seeds 9 rows with `appData.userDate=null` and distinct `created`
     * timestamps (so the SQL column has spread-out timestamps). Paginates
     * 3 at a time, asserts disjoint + exhaustive pages. Pre-fix: page 2
     * returns the same rows as page 1. Post-fix: pages tile the dataset.
     */
    @Test
    fun queryBatchAsync_oldestFirstUserDate_cursorAdvances_whenAppDataUserDateIsNull() {
        runBlocking {
            val dbm = DatabaseManager({ JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY) })

            val identityId = Uuid.fromLongs(0L, 1L)
            val driveId = Uuid.fromLongs(0L, 2L)
            val groupId = Uuid.fromLongs(0L, 3L)

            val totalRows = 9
            val pageSize = 3
            val baseCreated = 1_700_000_000_000L
            val expectedAllIds = (1..totalRows).map { i ->
                val fileId = Uuid.fromLongs(0L, 100L + i)
                seedChatMessage(
                    dbm = dbm,
                    identityId = identityId,
                    driveId = driveId,
                    groupId = groupId,
                    fileId = fileId,
                    uniqueId = Uuid.fromLongs(0L, 200L + i),
                    // userDate positional arg is unused when appDataUserDate=null
                    // — SQL column falls back to `created`. Keep a placeholder.
                    userDate = 0L,
                    created = baseCreated + i,
                    modified = baseCreated + i,
                    appDataUserDate = null,
                )
                fileId
            }.toSet()

            val qb = QueryBatch(identityId)
            val pages = mutableListOf<Set<Uuid>>()
            var cursor: QueryBatchCursor? = null
            var hasMore = true
            var safetyCounter = 0
            while (hasMore) {
                if (++safetyCounter > 6) {
                    error(
                        "cursor never advanced when appData.userDate=null — paged ${pages.size} " +
                            "times without exhausting the 9-row dataset (pages so far: $pages)"
                    )
                }
                val batch = qb.queryBatchAsync(
                    dbm = dbm,
                    driveId = driveId,
                    noOfItems = pageSize,
                    cursor = cursor,
                    sortOrder = QueryBatchSortOrder.OldestFirst,
                    sortField = QueryBatchSortField.UserDate,
                    fileSystemType = 0,
                    filetypesAnyOf = listOf(CHAT_MESSAGE_FILE_TYPE),
                    groupIdAnyOf = listOf(groupId),
                )
                val pageIds = batch.records.map { it.fileId }.toSet()
                for ((idx, prior) in pages.withIndex()) {
                    val overlap = pageIds.intersect(prior)
                    assertTrue(
                        overlap.isEmpty(),
                        "[null appData.userDate] page #${pages.size + 1} overlaps with page #${idx + 1}: " +
                            "overlap=$overlap thisPage=$pageIds priorPage=$prior",
                    )
                }
                pages += pageIds
                cursor = batch.cursor
                hasMore = batch.hasMoreRows
            }

            assertEquals(
                3, pages.size,
                "with 9 rows whose appData.userDate is null (SQL falls back to created), " +
                    "pagination should yield exactly 3 disjoint pages (got ${pages.size}: $pages)",
            )
            val union = pages.fold(emptySet<Uuid>()) { acc, p -> acc + p }
            assertEquals(
                expectedAllIds, union,
                "union of all pages should equal the seeded set — missing=" +
                    "${expectedAllIds - union} extra=${union - expectedAllIds}",
            )
        }
    }

    /**
     * Mirror of the OldestFirst variant above but in the *opposite* direction.
     *
     * The fix sets `cursor.row` to the *last-processed* row's actual `rowId`
     * regardless of sortOrder. For OldestFirst the last-processed row is the
     * one with the **largest** `(time, rowId)` returned this page (ORDER BY
     * ASC), so the next page's `(time, rowId) > (lastTime, lastRowId)`
     * correctly excludes it. For NewestFirst the last-processed row is the
     * one with the **smallest** `(time, rowId)` returned this page (ORDER BY
     * DESC), and the next page's `(time, rowId) < (lastTime, lastRowId)`
     * correctly excludes that one. The fix is direction-agnostic because the
     * `<`/`>` sign chosen at QueryBatch.kt:192-198 already encodes the
     * direction; the cursor's `row` half just has to be "the boundary you
     * stopped at".
     *
     * Starts with `cursor = null` so the first call exercises the
     * QueryBatch initialization path (no `paging?.let` branch, no `< MAX`
     * or `> 0` default), then threads the returned cursor through
     * subsequent pages.
     */
    @Test
    fun queryBatchAsync_newestFirstUserDate_paginatesDisjointlyWhenAllRowsShareUserDate() {
        runBlocking {
            val dbm = DatabaseManager({ JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY) })

            val identityId = Uuid.fromLongs(0L, 1L)
            val driveId = Uuid.fromLongs(0L, 2L)
            val groupId = Uuid.fromLongs(0L, 3L)

            val totalRows = 9
            val pageSize = 3
            val expectedAllIds = (1..totalRows).map { i ->
                val fileId = Uuid.fromLongs(0L, 100L + i)
                seedChatMessage(
                    dbm = dbm,
                    identityId = identityId,
                    driveId = driveId,
                    groupId = groupId,
                    fileId = fileId,
                    uniqueId = Uuid.fromLongs(0L, 200L + i),
                    userDate = 0L,
                )
                fileId
            }.toSet()

            val qb = QueryBatch(identityId)
            val pages = mutableListOf<Set<Uuid>>()
            // null on first call: exercises the "no incoming cursor" init path.
            // Subsequent calls thread the returned cursor through.
            var cursor: QueryBatchCursor? = null
            var hasMore = true
            var safetyCounter = 0
            while (hasMore) {
                if (++safetyCounter > 6) {
                    error(
                        "cursor never advanced for NewestFirst — paged ${pages.size} times " +
                            "without exhausting the 9-row dataset (pages so far: $pages)"
                    )
                }
                val batch = qb.queryBatchAsync(
                    dbm = dbm,
                    driveId = driveId,
                    noOfItems = pageSize,
                    cursor = cursor,
                    sortOrder = QueryBatchSortOrder.NewestFirst,
                    sortField = QueryBatchSortField.UserDate,
                    fileSystemType = 0,
                    filetypesAnyOf = listOf(CHAT_MESSAGE_FILE_TYPE),
                    groupIdAnyOf = listOf(groupId),
                )
                val pageIds = batch.records.map { it.fileId }.toSet()
                for ((idx, prior) in pages.withIndex()) {
                    val overlap = pageIds.intersect(prior)
                    assertTrue(
                        overlap.isEmpty(),
                        "[NewestFirst] page #${pages.size + 1} overlaps with page #${idx + 1}: " +
                            "overlap=$overlap thisPage=$pageIds priorPage=$prior",
                    )
                }
                pages += pageIds
                cursor = batch.cursor
                hasMore = batch.hasMoreRows
            }

            assertEquals(
                3, pages.size,
                "with 9 rows at the same userDate and pageSize=3, NewestFirst pagination " +
                    "should yield exactly 3 disjoint pages (got ${pages.size}: $pages)",
            )
            val union = pages.fold(emptySet<Uuid>()) { acc, p -> acc + p }
            assertEquals(
                expectedAllIds, union,
                "union of all NewestFirst pages should equal the seeded set — missing=" +
                    "${expectedAllIds - union} extra=${union - expectedAllIds}",
            )
        }
    }

    // ------------------------------------------------------------------------
    // One small cursor-advance test per non-UserDate sortField branch.
    //
    // The fix in `QueryBatch.kt` carries the boundary `rowId` through into the
    // next cursor for *all* five sortField branches via a shared
    // `boundaryRowId` local — UserDate, AnyChangeDate, OnlyModifiedDate,
    // FileId, CreatedDate. The UserDate path is covered above; these tests pin
    // the other branches so a future regression that re-introduces a
    // hardcoded `0L` in any of them gets caught.
    //
    // Each test seeds rows sharing the relevant timestamp (the condition that
    // makes the rowId tiebreaker actually matter), fetches one page, then a
    // second page using the returned cursor, and asserts no overlap.
    // ------------------------------------------------------------------------

    @Test
    fun queryBatchAsync_createdDate_cursorAdvancesPastTiedCreatedRows() {
        runBlocking {
            val dbm = DatabaseManager({ JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY) })
            val identityId = Uuid.fromLongs(0L, 1L)
            val driveId = Uuid.fromLongs(0L, 2L)
            val groupId = Uuid.fromLongs(0L, 3L)

            val tied = 1_700_000_000_000L
            // Distinct userDates (irrelevant for CreatedDate sort) but identical
            // `created`. CreatedDate's cursor builds from header.fileMetadata.created.
            for (i in 1..6) {
                seedChatMessage(
                    dbm, identityId, driveId, groupId,
                    fileId = Uuid.fromLongs(0L, 100L + i),
                    uniqueId = Uuid.fromLongs(0L, 200L + i),
                    userDate = tied + i,
                    created = tied,
                    modified = tied + i,
                )
            }

            val firstAndSecond = twoPagesDisjoint(
                dbm,
                identityId,
                driveId,
                groupId,
                sortField = QueryBatchSortField.CreatedDate,
            )
            assertEquals(3, firstAndSecond.first.size)
            assertEquals(3, firstAndSecond.second.size)
        }
    }

    @Test
    fun queryBatchAsync_fileId_cursorAdvancesPastTiedCreatedRows() {
        // FileId shares the CreatedDate branch in QueryBatch (both read
        // `header.fileMetadata.created` for the cursor). Same setup as above,
        // different sortField, must produce the same disjoint pagination.
        runBlocking {
            val dbm = DatabaseManager({ JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY) })
            val identityId = Uuid.fromLongs(0L, 1L)
            val driveId = Uuid.fromLongs(0L, 2L)
            val groupId = Uuid.fromLongs(0L, 3L)

            val tied = 1_700_000_000_000L
            for (i in 1..6) {
                seedChatMessage(
                    dbm, identityId, driveId, groupId,
                    fileId = Uuid.fromLongs(0L, 100L + i),
                    uniqueId = Uuid.fromLongs(0L, 200L + i),
                    userDate = tied + i,
                    created = tied,
                    modified = tied + i,
                )
            }

            val firstAndSecond = twoPagesDisjoint(
                dbm,
                identityId,
                driveId,
                groupId,
                sortField = QueryBatchSortField.FileId,
            )
            assertEquals(3, firstAndSecond.first.size)
            assertEquals(3, firstAndSecond.second.size)
        }
    }

    @Test
    fun queryBatchAsync_anyChangeDate_cursorAdvancesPastTiedModifiedRows() {
        runBlocking {
            val dbm = DatabaseManager({ JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY) })
            val identityId = Uuid.fromLongs(0L, 1L)
            val driveId = Uuid.fromLongs(0L, 2L)
            val groupId = Uuid.fromLongs(0L, 3L)

            // AnyChangeDate adds a WHERE `modified < now()` clause, so the
            // tied `modified` must be in the past. 1.7e12 ms ≈ 2023 — well
            // before now and safely in the past for any reasonable CI clock.
            val tied = 1_700_000_000_000L
            for (i in 1..6) {
                seedChatMessage(
                    dbm, identityId, driveId, groupId,
                    fileId = Uuid.fromLongs(0L, 100L + i),
                    uniqueId = Uuid.fromLongs(0L, 200L + i),
                    userDate = tied + i,
                    created = tied + i,
                    modified = tied,  // ← tied modified — the AnyChangeDate cursor key
                )
            }

            val firstAndSecond = twoPagesDisjoint(
                dbm,
                identityId,
                driveId,
                groupId,
                sortField = QueryBatchSortField.AnyChangeDate,
            )
            assertEquals(3, firstAndSecond.first.size)
            assertEquals(3, firstAndSecond.second.size)
        }
    }

    @Test
    fun queryBatchAsync_onlyModifiedDate_cursorAdvancesPastTiedModifiedRows() {
        runBlocking {
            val dbm = DatabaseManager({ JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY) })
            val identityId = Uuid.fromLongs(0L, 1L)
            val driveId = Uuid.fromLongs(0L, 2L)
            val groupId = Uuid.fromLongs(0L, 3L)

            // OnlyModifiedDate additionally requires `modified != created`,
            // so seed with distinct `created` per row and an identical `modified`.
            val tied = 1_700_000_000_000L
            for (i in 1..6) {
                seedChatMessage(
                    dbm, identityId, driveId, groupId,
                    fileId = Uuid.fromLongs(0L, 100L + i),
                    uniqueId = Uuid.fromLongs(0L, 200L + i),
                    userDate = tied + i,
                    created = 1_500_000_000_000L + i,  // distinct, in the past, != modified
                    modified = tied,
                )
            }

            val firstAndSecond = twoPagesDisjoint(
                dbm,
                identityId,
                driveId,
                groupId,
                sortField = QueryBatchSortField.OnlyModifiedDate,
            )
            assertEquals(3, firstAndSecond.first.size)
            assertEquals(3, firstAndSecond.second.size)
        }
    }

    /**
     * Helper for the per-sortField tests: fetch two pages of 3 rows and
     * return them as a pair. Asserts inline that both batches are full and
     * that the second batch contains no rows from the first.
     */
    private suspend fun twoPagesDisjoint(
        dbm: DatabaseManager,
        identityId: Uuid,
        driveId: Uuid,
        groupId: Uuid,
        sortField: QueryBatchSortField,
    ): Pair<Set<Uuid>, Set<Uuid>> {
        val qb = QueryBatch(identityId)
        val first = qb.queryBatchAsync(
            dbm = dbm,
            driveId = driveId,
            noOfItems = 3,
            cursor = null,
            sortOrder = QueryBatchSortOrder.OldestFirst,
            sortField = sortField,
            fileSystemType = 0,
            filetypesAnyOf = listOf(CHAT_MESSAGE_FILE_TYPE),
            groupIdAnyOf = listOf(groupId),
        )
        val second = qb.queryBatchAsync(
            dbm = dbm,
            driveId = driveId,
            noOfItems = 3,
            cursor = first.cursor,
            sortOrder = QueryBatchSortOrder.OldestFirst,
            sortField = sortField,
            fileSystemType = 0,
            filetypesAnyOf = listOf(CHAT_MESSAGE_FILE_TYPE),
            groupIdAnyOf = listOf(groupId),
        )
        val firstIds = first.records.map { it.fileId }.toSet()
        val secondIds = second.records.map { it.fileId }.toSet()
        val overlap = firstIds.intersect(secondIds)
        assertTrue(
            overlap.isEmpty(),
            "[$sortField] second page must not overlap first — overlap=$overlap " +
                "firstIds=$firstIds secondIds=$secondIds",
        )
        return firstIds to secondIds
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
        created: Long = userDate,
        modified: Long = userDate,
        // When `appDataUserDate` is null, the JSON emits `"userDate": null`
        // and the SQL `userDate` column falls back to `created.milliseconds`
        // (mirroring `MainIndexMeta.kt:127-128`). This is the "no version /
        // not edited" branch in production for chat messages from older
        // clients — and the case that exposes the cursor-userDate-source bug
        // fixed in [queryBatchAsync_oldestFirstUserDate_cursorAdvances_whenAppDataUserDateIsNull].
        appDataUserDate: Long? = userDate,
    ) {
        val jsonHeader = buildChatMessageJson(
            fileId = fileId,
            driveId = driveId,
            uniqueId = uniqueId,
            groupId = groupId,
            appDataUserDate = appDataUserDate,
            created = created,
            modified = modified,
        )
        // SQL `userDate` column mirrors the production projection:
        // `appData.userDate ?: created`. Tests that seed `appDataUserDate=null`
        // get `created` in the SQL column, matching real-world rows.
        val sqlUserDate = appDataUserDate ?: created
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
            userDate = sqlUserDate,
            created = created,
            modified = modified,
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
        appDataUserDate: Long?,
        created: Long,
        modified: Long,
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
                "created": $created,
                "updated": $modified,
                "transitCreated": $created,
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
                    "userDate": ${appDataUserDate ?: "null"},
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
