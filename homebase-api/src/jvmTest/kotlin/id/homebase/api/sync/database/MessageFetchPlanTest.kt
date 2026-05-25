package id.homebase.api.sync.database

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Regression guard for the conversation-open "spinner": the per-conversation chat message
 * fetch must use the selective per-conversation index seek ([Idx3DriveMainIndex]), not a global
 * userDate scan ([Idx2DriveMainIndex]).
 *
 * Without ANALYZE statistics (production state — SQLDelight/SQLCipher never run ANALYZE) SQLite
 * can't tell `groupId` is selective and plans the global scan, so opening an old/sparse
 * conversation walks the whole newest→oldest timeline (~900ms on-device). [DatabaseManager.optimize]
 * (PRAGMA analysis_limit + optimize) gives the planner stats and flips it to the index seek
 * (sub-ms). This test proves that flip on the same single in-memory driver production uses.
 */
class MessageFetchPlanTest {

    // Mirrors the SQL QueryBatch emits for ChatMessageStream.fetchMessages: a per-conversation
    // page filtered by identityId+driveId+fileSystemType+fileState+groupId+fileType, paged by a
    // (userDate,rowId) cursor, newest-first. Literals stand in for bound params; the chosen plan
    // is identical.
    private val messageFetchSql = """
        SELECT DISTINCT rowId, jsonHeader FROM DriveMainIndex
        WHERE (userDate, rowId) < (2000000000, 0)
          AND identityId = x'00' AND driveId = x'01' AND fileSystemType = 0
          AND fileState = 1 AND fileType IN (7878) AND groupId IN (x'3b')
        ORDER BY userDate DESC, rowId DESC LIMIT 50
    """.trimIndent()

    @Test
    fun optimizeFlipsMessageFetchFromGlobalScanToPerConversationSeek() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        // Build schema + seed on the raw driver BEFORE constructing a DatabaseManager, so the
        // baseline is captured with no ANALYZE stats. (DatabaseManager.init kicks off optimize()
        // asynchronously — establishing the baseline first keeps this deterministic.)
        OdinDatabase.Schema.create(driver)
        seedMessages(driver)

        // Production state: no stats yet → global userDate scan (the regression).
        val before = explainPlan(driver, messageFetchSql)
        assertTrue(
            before.contains("Idx2DriveMainIndex"),
            "without stats the planner should fall back to the global userDate scan; was:\n$before",
        )
        assertTrue(
            !before.contains("idx_chatmessage_convid_userDate") && !before.contains("Idx3DriveMainIndex"),
            "without stats it should NOT already use a per-conversation seek; was:\n$before",
        )

        DatabaseManager({ driver }).use { dbm ->
            dbm.optimize()

            // After optimize the planner must use a per-conversation seek (a groupId-leading
            // index) rather than the global userDate scan. SQLite picks whichever groupId index
            // it judges best — idx_chatmessage_convid_userDate (groupId,fileType,userDate) or
            // Idx3DriveMainIndex (…,groupId,fileType,userDate); the bundled version varies, and
            // either is the fast path. The regression signal is simply: NOT the Idx2 global scan.
            val after = explainPlan(driver, messageFetchSql)
            assertTrue(
                after.contains("idx_chatmessage_convid_userDate") || after.contains("Idx3DriveMainIndex"),
                "after optimize the planner should use a per-conversation (groupId) index seek; was:\n$after",
            )
            assertTrue(
                !after.contains("Idx2DriveMainIndex"),
                "after optimize it should no longer use the global userDate scan; was:\n$after",
            )
        }
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
