package id.homebase.api.sync.database

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Regression guard for the conversation-open "spinner": without intervention, opening an
 * old/quiet conversation walked the global newest→oldest timeline filtering by `groupId`
 * per row (~hundreds of ms to seconds on-device). Pin the per-conversation index for
 * `QueryBatch`'s chat-message-fetch shape so the planner always seeks by `groupId`
 * regardless of query-planner statistics or connection state. Moments-style scans (no
 * `groupId` filter) must NOT be affected — they should continue using the global userDate
 * index for newest-first scans.
 *
 * This test asserts both branches by `EXPLAIN QUERY PLAN` on the same in-memory driver
 * production uses.
 */
class MessageFetchPlanTest {

    private val chatFetchSql = """
        SELECT DISTINCT rowId, jsonHeader FROM DriveMainIndex INDEXED BY idx_chatmessage_convid_userDate
        WHERE (userDate, rowId) < (2000000000, 0)
          AND identityId = x'00' AND driveId = x'01' AND fileSystemType = 0
          AND fileType IN (7878) AND groupId IN (x'3b')
        ORDER BY userDate DESC, rowId DESC LIMIT 50
    """.trimIndent()

    private val momentsFetchSql = """
        SELECT DISTINCT rowId, jsonHeader FROM DriveMainIndex
        WHERE (userDate, rowId) < (2000000000, 0)
          AND identityId = x'00' AND driveId = x'01' AND fileSystemType = 0
          AND fileType IN (8888)
        ORDER BY userDate DESC, rowId DESC LIMIT 50
    """.trimIndent()

    @Test
    fun chatShape_usesPerConversationIndex() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        OdinDatabase.Schema.create(driver)
        seedMessages(driver)
        val plan = explainPlan(driver, chatFetchSql)
        assertTrue(
            plan.contains("idx_chatmessage_convid_userDate"),
            "the chat fetch (with INDEXED BY) should use the per-conversation index; was:\n$plan",
        )
        assertTrue(
            !plan.contains("Idx2DriveMainIndex"),
            "the chat fetch must not fall to the global userDate scan; was:\n$plan",
        )
    }

    @Test
    fun momentsShape_stillUsesGlobalUserDateIndex() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        OdinDatabase.Schema.create(driver)
        seedMessages(driver)
        val plan = explainPlan(driver, momentsFetchSql)
        assertTrue(
            plan.contains("Idx2DriveMainIndex"),
            "Moments-style scans (no groupId filter) should still use the global userDate " +
                "index; was:\n$plan",
        )
        assertTrue(
            !plan.contains("idx_chatmessage_convid_userDate"),
            "Moments-style scans must not get pinned to the chat index; was:\n$plan",
        )
    }

    /**
     * The pinned-messages bar refreshes on every conversation open. Unpinned, SQLite
     * drives the join from DriveMainIndex — seeking the conversation by `groupId` and
     * probing the tag table once per message — so open cost grows with the conversation's
     * length. The tag table must be the outer loop: seek the handful of tagged files
     * directly, then look each one up by fileId.
     */
    @Test
    fun pinnedBarShape_drivesJoinFromTagIndex() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        OdinDatabase.Schema.create(driver)
        seedMessages(driver)
        seedLocalTags(driver)
        val plan = explainPlan(driver, pinnedBarSql)
        val tagLine = plan.lineSequence().firstOrNull { it.contains(" t ") }.orEmpty()
        assertTrue(
            tagLine.contains("idx_localtag_tag"),
            "the pinned-bar refresh must seek the tag table via idx_localtag_tag; was:\n$plan",
        )
        assertTrue(
            plan.lineSequence().indexOfFirst { it.contains(" t ") } <
                plan.lineSequence().indexOfFirst { it.contains(" m ") },
            "the tag table must be the OUTER loop; driving from DriveMainIndex probes the " +
                "tag table once per message in the conversation. Plan was:\n$plan",
        )
    }

    private val pinnedBarSql = """
        SELECT m.jsonHeader
        FROM DriveLocalTagIndex t INDEXED BY idx_localtag_tag
        JOIN DriveMainIndex m
          ON m.identityId = t.identityId AND m.driveId = t.driveId AND m.fileId = t.fileId
        WHERE t.identityId = x'00' AND t.driveId = x'01' AND t.tagId = x'4d'
          AND m.groupId = x'3b' AND m.fileType = 7878
          AND m.fileState = 1 AND m.archivalStatus != 2
        ORDER BY m.userDate DESC
    """.trimIndent()

    /** One local tag per message — production writes pending/failed/pin/dismiss markers. */
    private fun seedLocalTags(driver: SqlDriver) {
        driver.execute(
            null,
            """
            INSERT INTO DriveLocalTagIndex(identityId, driveId, fileId, tagId)
            SELECT identityId, driveId, fileId,
                   CASE WHEN groupId = x'3b' THEN x'4d' ELSE randomblob(16) END
            FROM DriveMainIndex
            """.trimIndent(),
            0,
        )
    }

    /**
     * Many recent messages spread across many conversations + one OLD, SPARSE target
     * (groupId x'3b', 7 rows at low userDate). This is the shape that makes the global-scan
     * plan pathological: the target's few rows sit deep below everyone else's newer messages.
     */
    private fun seedMessages(driver: SqlDriver) {
        driver.execute(
            null,
            """
            WITH RECURSIVE seq(n) AS (SELECT 1 UNION ALL SELECT n+1 FROM seq WHERE n < 5000)
            INSERT INTO DriveMainIndex(
                identityId, driveId, fileId, groupId, fileType, dataType, archivalStatus,
                fileState, historyStatus, userDate, created, modified, fileSystemType, jsonHeader)
            SELECT x'00', x'01', randomblob(16),
                   CASE WHEN n <= 7 THEN x'3b' ELSE randomblob(16) END,
                   7878, 0, 1, 1, 0,
                   CASE WHEN n <= 7 THEN n ELSE 1000000 + n END,
                   n, n, 0, 'hdr'
            FROM seq
            """.trimIndent(),
            0,
        )
    }

    private fun explainPlan(driver: SqlDriver, sql: String): String =
        driver.executeQuery(
            identifier = null,
            sql = "EXPLAIN QUERY PLAN $sql",
            mapper = { cursor ->
                val sb = StringBuilder()
                while (cursor.next().value) {
                    sb.append(cursor.getString(3) ?: "").append('\n') // column 3 = "detail"
                }
                QueryResult.Value(sb.toString())
            },
            parameters = 0,
        ).value
}
