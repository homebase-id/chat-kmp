package id.homebase.api.sync.database

import app.cash.sqldelight.db.SqlDriver

/** The on-disk database file name used by every platform's [DatabaseDriverFactory]. */
internal const val DB_FILE_NAME = "odin-2.db"

/**
 * Shared SQLite tuning applied identically on every platform — the single source of
 * truth so the three [DatabaseDriverFactory] actuals can't drift. Each platform applies
 * it through its driver's native mechanism (desktop: JDBC connection [java.util.Properties];
 * Android: `execSQL` in the open-helper callback; iOS: `rawExecSql` in the sqliter
 * connection lifecycle), but the pragma set itself lives here.
 *
 *  - `journal_mode=WAL` — readers and the single writer don't block each other. Reads
 *    bypass [DatabaseManager]'s single-writer dispatcher and run on separate connections,
 *    so without WAL a long read + a write collide into `SQLITE_BUSY` (what knocked the
 *    desktop WebSocket offline). File-persistent.
 *  - `busy_timeout=5000` — a contended statement waits up to 5s for the lock instead of
 *    throwing `SQLITE_BUSY` immediately.
 *  - `synchronous=NORMAL` — faster, WAL-safe durability (no corruption risk; at most the
 *    last committed transaction is lost on power loss).
 *  - `temp_store=MEMORY` — temp tables/indices in RAM.
 */
internal val SQLITE_TUNING_PRAGMAS: Map<String, String> = linkedMapOf(
    "journal_mode" to "WAL",
    "busy_timeout" to "5000",
    "synchronous" to "NORMAL",
    "temp_store" to "MEMORY",
)

/**
 * [SQLITE_TUNING_PRAGMAS] rendered as executable `PRAGMA key=value` statements, for the
 * platforms that apply pragmas as raw SQL on each new connection (Android, iOS).
 */
internal fun sqliteTuningPragmaStatements(): List<String> =
    SQLITE_TUNING_PRAGMAS.entries.map { (key, value) -> "PRAGMA $key=$value" }

@Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
expect class DatabaseDriverFactory {
    fun createDriver(passphrase: String? = null): SqlDriver

    /**
     * Open a SECOND connection to the same database for [DatabaseManager]'s concurrent
     * read lane — under WAL it reads committed snapshots without blocking (or being
     * blocked by) the single writer connection. Returns null on platforms with no
     * separate read connection to give (wasm's in-memory sql.js), where reads fall back
     * to the writer lane.
     *
     * MUST be called only after [createDriver] has opened/created the schema: this
     * connection never creates schema, it just attaches to the existing file.
     */
    fun createReadDriver(passphrase: String? = null): SqlDriver?

    /**
     * Absolute path to the on-disk database file on this platform. Used by
     * [deleteOnDiskFiles] to clean up after a failed open in
     * [DatabaseManager.initializeWithRecovery]. Each platform reports the
     * exact path its `createDriver` opens (or would open) so the recovery
     * deletes the file the driver was looking at.
     *
     * Platforms without an on-disk database (e.g. wasmJs's in-memory sql.js)
     * return an empty string — [deleteOnDiskFiles] becomes a no-op.
     */
    fun dbFilePath(): String
}

/**
 * Delete a single SQLite side file at [path] if it exists. Per-platform so
 * commonMain doesn't have to reference `kotlinx.io.files.SystemFileSystem`,
 * which is unavailable on wasmJs. Missing files are treated as a no-op
 * (matches `SystemFileSystem.delete(..., mustExist = false)`).
 *
 * On wasmJs this is a no-op because sql.js is in-memory and has no on-disk
 * file to delete.
 */
internal expect fun deleteSqliteFileIfExists(path: String)

/**
 * Delete the on-disk database file plus its WAL/SHM/journal siblings. The
 * loop and suffix list live in commonMain because they aren't
 * platform-specific — only [DatabaseDriverFactory.dbFilePath] is. Missing
 * files are treated as a no-op.
 */
internal fun DatabaseDriverFactory.deleteOnDiskFiles() {
    deleteDatabaseFiles(dbFilePath())
}

/**
 * Delete the database file at [dbFilePath] plus its WAL/SHM/journal
 * siblings. Extracted from [DatabaseDriverFactory.deleteOnDiskFiles] so
 * unit tests can exercise the suffix loop against a temp-directory path
 * without instantiating a real factory (which would target the user's
 * actual data directory).
 *
 * SQLite writes up to three side files alongside the primary database:
 *   - `-journal` (rollback journal mode)
 *   - `-wal` (write-ahead log mode)
 *   - `-shm` (WAL shared-memory index)
 * All three may exist at recovery time depending on which mode last
 * touched the file; deleting the primary alone would leave the others as
 * zombies.
 */
internal fun deleteDatabaseFiles(dbFilePath: String) {
    if (dbFilePath.isEmpty()) return
    for (suffix in listOf("", "-journal", "-wal", "-shm")) {
        deleteSqliteFileIfExists(dbFilePath + suffix)
    }
}
