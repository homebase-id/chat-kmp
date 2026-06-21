package id.homebase.api.sync.database

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Regression coverage for the iOS launch crash:
 *
 * ```
 * error while compiling: CREATE INDEX IF NOT EXISTS idx_unread_cover
 *   ON DriveMainIndex(identityId, fileType, dataType, groupId, userDate,
 *                     originalAuthor, fileState, archivalStatus)
 * no such column: fileState
 * ```
 *
 * Cause (platform-agnostic): `DatabaseManager.init {}` runs
 * `OdinDatabase.Schema.create(driver)` *before* the `-- Version:` comment check
 * that would otherwise `wipeAndRecreate()` a stale database. `fileState` was added
 * to DriveMainIndex (schema v4→5) and a *new* covering index `idx_unread_cover`
 * over it shipped afterwards. On a device whose DriveMainIndex predates `fileState`:
 *   - `CREATE TABLE IF NOT EXISTS DriveMainIndex(...)` is a no-op (old table kept), and
 *   - `CREATE INDEX IF NOT EXISTS idx_unread_cover ON DriveMainIndex(... fileState ...)`
 *     throws `no such column: fileState`. The index is *new*, so `IF NOT EXISTS`
 *     does not short-circuit it — it evaluates its column list against the stale table.
 *
 * These tests run on every disk-backed target (JVM, Android host, **iOS** via
 * `nativeTest`/`iosSimulatorArm64Test`), pinning the root cause where the users
 * actually hit it. wasmJs is excluded (no SQLDelight test driver) in build.gradle.kts.
 *
 * [staleSchemaThrowsOnNewFileStateIndex] characterizes the crash; [dropBeforeCreateRecoversCleanly]
 * proves the intended `wipeAndRecreate()` mechanism (DROP every table, then create)
 * is sound — i.e. the defect is purely the create-before-version-check ordering.
 */
class DriveMainIndexCreateOrderTest {

    private val driver: SqlDriver = createRawInMemoryDriver()

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    @Test
    fun staleSchemaThrowsOnNewFileStateIndex() {
        stageOldDriveMainIndex(driver)

        // Reproduce the production launch path: apply the current schema over the
        // pre-fileState DriveMainIndex. The new idx_unread_cover is the only new
        // index over fileState (the old Idx*/idx_chatmessage names already exist,
        // so their `IF NOT EXISTS` creates are no-ops and never re-check columns).
        val error = assertFailsWith<Throwable> {
            OdinDatabase.Schema.create(driver)
        }
        val message = error.message ?: ""
        assertTrue(
            message.contains("no such column", ignoreCase = true) &&
                message.contains("fileState"),
            "Expected a 'no such column: fileState' compile error, got: $message",
        )
    }

    @Test
    fun dropBeforeCreateRecoversCleanly() {
        stageOldDriveMainIndex(driver)

        // What wipeAndRecreate() does: DROP every table first, THEN create. The DROP
        // removes the stale DriveMainIndex (and its indexes) so the fresh CREATE TABLE
        // builds the v5 shape with fileState before any index references it.
        DatabaseManager.TABLE_NAMES.forEach { table ->
            driver.execute(null, "DROP TABLE IF EXISTS $table", 0)
        }

        // Must not throw.
        OdinDatabase.Schema.create(driver)

        val columns = queryStrings(driver, "PRAGMA table_info(DriveMainIndex)", column = 1)
        assertTrue(
            columns.contains("fileState"),
            "Recreated DriveMainIndex should carry fileState, has: $columns",
        )

        val indexes = queryStrings(
            driver,
            "SELECT name FROM sqlite_master WHERE type = 'index'",
            column = 0,
        )
        assertTrue(
            indexes.contains("idx_unread_cover"),
            "Recreated schema should include idx_unread_cover, has: $indexes",
        )
        // Sanity: a fresh recreate leaves no rows behind.
        val rowCount = queryStrings(driver, "SELECT COUNT(*) FROM DriveMainIndex", column = 0)
        assertEquals(listOf("0"), rowCount, "Recreated DriveMainIndex should be empty")
    }

    /**
     * Stage a pre-`fileState` DriveMainIndex exactly as an old install carries it:
     * the v4 table plus its v4 indexes (Idx0..Idx3 — none reference fileState — and
     * idx_chatmessage_convid_userDate). Because every old index name already exists,
     * the current schema's `CREATE INDEX IF NOT EXISTS` for those names is a no-op and
     * the failure lands on the genuinely new `idx_unread_cover`, matching the report.
     *
     * Plain SQLite types only (no SQLDelight `AS Uuid` adapter syntax) since this is
     * raw DDL executed on the driver. Sourced from
     * `git show f555d9816^:.../DriveMainIndex.sq`.
     */
    private fun stageOldDriveMainIndex(driver: SqlDriver) {
        driver.execute(
            null,
            """
            CREATE TABLE IF NOT EXISTS DriveMainIndex( -- Version: 4
               rowId INTEGER PRIMARY KEY AUTOINCREMENT,
               identityId BLOB NOT NULL,
               driveId BLOB NOT NULL,
               fileId BLOB NOT NULL,
               uniqueId BLOB,
               globalTransitId BLOB,
               senderId TEXT,
               originalAuthor TEXT,
               groupId BLOB,
               fileType INTEGER NOT NULL,
               dataType INTEGER NOT NULL,
               archivalStatus INTEGER NOT NULL,
               historyStatus INTEGER NOT NULL,
               userDate INTEGER NOT NULL,
               created INTEGER NOT NULL,
               modified INTEGER NOT NULL,
               fileSystemType INTEGER NOT NULL,
               jsonHeader TEXT NOT NULL,
               UNIQUE(identityId,driveId,fileId),
               UNIQUE(identityId,driveId,uniqueId),
               UNIQUE(identityId,driveId,globalTransitId)
            )
            """.trimIndent(),
            0,
        )
        listOf(
            "CREATE INDEX IF NOT EXISTS Idx0DriveMainIndex ON DriveMainIndex(identityId,driveId,fileSystemType,created,rowId)",
            "CREATE INDEX IF NOT EXISTS Idx1DriveMainIndex ON DriveMainIndex(identityId,driveId,fileSystemType,modified,rowId)",
            "CREATE INDEX IF NOT EXISTS Idx2DriveMainIndex ON DriveMainIndex(identityId,driveId,fileSystemType,userDate,rowId)",
            "CREATE INDEX IF NOT EXISTS Idx3DriveMainIndex ON DriveMainIndex(identityId,driveId,fileSystemType,groupId,fileType,userDate DESC,rowId)",
            "CREATE INDEX IF NOT EXISTS idx_chatmessage_convid_userDate ON DriveMainIndex(groupId,fileType,userDate DESC)",
        ).forEach { driver.execute(null, it, 0) }
    }

    /** Collect one text column from a query into a list (sync drivers only). */
    private fun queryStrings(driver: SqlDriver, sql: String, column: Int): List<String> {
        val out = mutableListOf<String>()
        driver.executeQuery(
            identifier = null,
            sql = sql,
            mapper = { cursor ->
                while (cursor.next().value) {
                    cursor.getString(column)?.let(out::add)
                }
                QueryResult.Value(Unit)
            },
            parameters = 0,
        )
        return out
    }
}
