@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.api.sync.database

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * #1199 — a message that sat in the sender's outbox arrives stamped with its
 * compose time, so `DriveMainIndex.userDate` lands *below* `lastReadTime` and
 * the old `d.userDate > c.lastReadTime` filter never counted it unread.
 * dotyoucore-js counts unread on `transitCreated || created` (arrival); these
 * pin the ported `MAX(userDate, created)` predicate and the covering index that
 * keeps it off a per-row table lookup.
 */
class UnreadCountLateArrivalTest {

    private val identityId = Uuid.parse("00000000-0000-0000-0000-0000000000aa")
    private val groupId = Uuid.parse("00000000-0000-0000-0000-0000000000bb")
    private val self = "owner.test"
    private val peer = "peer.test"

    // 12:00, and a message composed at 10:30 that only reached us at 12:02.
    private val readMessageMs = 1_780_000_000_000L
    private val lateComposedMs = readMessageMs - 90 * 60_000L
    private val lateArrivedMs = readMessageMs + 2 * 60_000L

    private fun queries(driver: SqlDriver) = ChatReadCountQueries(
        driver,
        DriveMainIndex.Adapter(
            identityIdAdapter = UuidAdapter,
            driveIdAdapter = UuidAdapter,
            fileIdAdapter = UuidAdapter,
            globalTransitIdAdapter = UuidAdapter,
            groupIdAdapter = UuidAdapter,
            uniqueIdAdapter = UuidAdapter,
        ),
        ChatReadCount.Adapter(groupIdAdapter = UuidAdapter),
    )

    private fun seed(driver: SqlDriver) {
        fun insert(userDate: Long, created: Long) = driver.execute(
            null,
            """
            INSERT INTO DriveMainIndex(
                identityId, driveId, fileId, groupId, originalAuthor, fileType, dataType,
                archivalStatus, fileState, historyStatus, userDate, created, modified,
                fileSystemType, jsonHeader)
            VALUES (x'000000000000000000000000000000aa', x'01', randomblob(16),
                    x'000000000000000000000000000000bb', '$peer', 7878, 0,
                    0, 1, 0, $userDate, $created, $created, 0, 'hdr')
            """.trimIndent(),
            0,
        )
        // Already read: composed and delivered at 12:00.
        insert(readMessageMs, readMessageMs)
        // The #1199 message: composed 10:30, arrived 12:02.
        insert(lateComposedMs, lateArrivedMs)

        // Read up to the 12:00 message.
        queries(driver).upsertLastReadTime(groupId, readMessageMs)
    }

    private fun openSeeded(): SqlDriver {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        OdinDatabase.Schema.create(driver)
        seed(driver)
        return driver
    }

    @Test
    fun lateArrivingMessageIsCountedUnread() {
        val driver = openSeeded()
        val rows = queries(driver).selectAllUnreadCount(identityId, self).executeAsList()
        assertEquals(1, rows.size, "expected the one conversation to report unread")
        assertEquals(groupId, rows.single().groupId)
        assertEquals(
            1L,
            rows.single().unreadCount,
            "the message composed 90 min before lastReadTime but delivered after it must count",
        )
        driver.close()
    }

    @Test
    fun perConversationUnreadCountAgreesWithTheListQuery() {
        val driver = openSeeded()
        val count = queries(driver)
            .selectUnreadCountForConversation(identityId, groupId, self)
            .executeAsOne()
        assertEquals(1L, count)
        driver.close()
    }

    @Test
    fun composeTimeOnlyPredicateMissesIt() {
        // The pre-fix predicate, run against the same rows, to keep the
        // regression it fixes visible.
        val driver = openSeeded()
        val count = driver.executeQuery(
            null,
            """
            SELECT COUNT(*) FROM DriveMainIndex d
            LEFT JOIN ChatReadCount c ON d.groupId = c.groupId
            WHERE d.identityId = x'000000000000000000000000000000aa'
              AND d.fileType = 7878 AND d.dataType = 0
              AND (c.lastReadTime IS NULL OR d.userDate > c.lastReadTime)
              AND d.originalAuthor IS NOT NULL AND d.originalAuthor != '$self'
              AND d.fileState != 0 AND d.archivalStatus != 2
            """.trimIndent(),
            { cursor -> QueryResult.Value(cursor.next().value.let { cursor.getLong(0) }) },
            0,
        ).value
        assertEquals(0L, count, "sanity: compose-time-only filtering is what lost the message")
        driver.close()
    }

    @Test
    fun unreadCountStaysOnACoveringIndex() {
        val driver = openSeeded()
        val plan = StringBuilder()
        driver.executeQuery(
            null,
            """
            EXPLAIN QUERY PLAN
            SELECT d.groupId, COUNT(*) AS unreadCount, MAX(c.lastReadTime) AS lastReadTime
            FROM DriveMainIndex AS d
            LEFT JOIN ChatReadCount c ON d.groupId = c.groupId
            WHERE d.identityId = x'000000000000000000000000000000aa'
              AND d.fileType = 7878 AND d.dataType = 0
              AND (c.lastReadTime IS NULL OR MAX(d.userDate, d.created) > c.lastReadTime)
              AND d.groupId IS NOT NULL
              AND d.originalAuthor IS NOT NULL AND d.originalAuthor != '$self'
              AND d.fileState != 0 AND d.archivalStatus != 2
            GROUP BY d.groupId
            """.trimIndent(),
            { cursor ->
                while (cursor.next().value) plan.appendLine(cursor.getString(3))
                QueryResult.Value(Unit)
            },
            0,
        )
        assertTrue(
            plan.contains("COVERING INDEX idx_unread_cover_v2"),
            "unread scan must stay index-covering (no per-row table lookup); was:\n$plan",
        )
        driver.close()
    }
}
