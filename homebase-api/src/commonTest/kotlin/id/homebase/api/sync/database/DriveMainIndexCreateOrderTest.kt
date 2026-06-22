package id.homebase.api.sync.database

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import kotlin.test.Test
import kotlin.test.assertEquals
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
 * Cause (platform-agnostic): `DatabaseManager`'s `init {}` block runs
 * `OdinDatabase.Schema.create(driver)` *before* the `-- Version:` comment check
 * that would otherwise `wipeAndRecreate()` a stale database. `fileState` was added
 * to DriveMainIndex (schema v4→5) and a *new* covering index `idx_unread_cover`
 * over it shipped afterwards. On a device whose DriveMainIndex predates `fileState`:
 *   - `CREATE TABLE IF NOT EXISTS DriveMainIndex(...)` is a no-op (old table kept), and
 *   - `CREATE INDEX IF NOT EXISTS idx_unread_cover ON DriveMainIndex(... fileState ...)`
 *     throws `no such column: fileState`. The index is *new*, so `IF NOT EXISTS`
 *     does not short-circuit it — it evaluates its column list against the stale table.
 *
 * The crash is in the **constructor's `init` block**, which runs *before* the
 * companion `initialize()`'s version check — so the wipe net never fires.
 *
 * These tests run on every disk-backed target (JVM, Android host, **iOS** via
 * `nativeTest`/`iosSimulatorArm64Test`), pinning the root cause where the users
 * actually hit it. wasmJs is excluded (no SQLDelight test driver) in build.gradle.kts.
 */
class DriveMainIndexCreateOrderTest {

    /**
     * Drives the **production open path**: `DatabaseManager({ driver })` is the exact
     * constructor `DatabaseManager.initialize()` (and the existing OutboxTest /
     * LocationPointWrapperTest) use. Opening it over a pre-`fileState` DriveMainIndex
     * must yield a usable, current-schema database.
     *
     * RED until the create/version-check ordering is fixed: today the `init {}`
     * block's `Schema.create()` throws `no such column: fileState` on the stale table
     * before the version check can wipe it, so the constructor — and the app launch —
     * crashes. This is the regression that reproduces on the iOS toolchain in CI.
     */
    @Test
    fun databaseManagerOpensOverStaleSchema() {
        val raw = createRawInMemoryDriver()
        stageOldDriveMainIndex(raw)

        // Constructor runs the production init path. Must not crash; the DB must come
        // up on the current v5 schema (fileState present), via wipe-and-recreate.
        DatabaseManager({ raw }).use { dbm ->
            val columns = queryStrings(
                dbm.driver,
                "PRAGMA table_info(DriveMainIndex)",
                column = 1,
            )
            assertTrue(
                columns.contains("fileState"),
                "Opened DriveMainIndex should carry fileState (v5 schema), has: $columns",
            )
            val indexes = queryStrings(
                dbm.driver,
                "SELECT name FROM sqlite_master WHERE type = 'index'",
                column = 0,
            )
            assertTrue(
                indexes.contains("idx_unread_cover"),
                "Opened schema should include idx_unread_cover, has: $indexes",
            )
        }
    }

    /**
     * The intended recovery is sound: doing what `wipeAndRecreate()` does — DROP every
     * table, THEN create — turns a stale pre-`fileState` DriveMainIndex into the clean
     * v5 schema. Confirms the defect is purely the create-before-version-check ordering,
     * not the schema or the wipe routine itself. Passes today.
     */
    @Test
    fun dropBeforeCreateRecoversCleanly() {
        val raw = createRawInMemoryDriver()
        stageOldDriveMainIndex(raw)

        DatabaseManager.TABLE_NAMES.forEach { table ->
            raw.execute(null, "DROP TABLE IF EXISTS $table", 0)
        }
        OdinDatabase.Schema.create(raw) // must not throw

        val columns = queryStrings(raw, "PRAGMA table_info(DriveMainIndex)", column = 1)
        assertTrue(
            columns.contains("fileState"),
            "Recreated DriveMainIndex should carry fileState, has: $columns",
        )
        val indexes = queryStrings(
            raw,
            "SELECT name FROM sqlite_master WHERE type = 'index'",
            column = 0,
        )
        assertTrue(
            indexes.contains("idx_unread_cover"),
            "Recreated schema should include idx_unread_cover, has: $indexes",
        )
        val rowCount = queryStrings(raw, "SELECT COUNT(*) FROM DriveMainIndex", column = 0)
        assertEquals(listOf("0"), rowCount, "Recreated DriveMainIndex should be empty")
        raw.close()
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
