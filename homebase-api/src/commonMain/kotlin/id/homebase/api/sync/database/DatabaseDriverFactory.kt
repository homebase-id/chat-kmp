package id.homebase.api.sync.database

import app.cash.sqldelight.db.SqlDriver

/**
 * The on-disk database file name used by every platform's [DatabaseDriverFactory].
 *
 * **This name is permanent — do not bump it.** The `-2` is historical: it was once
 * changed (from `odin.db`) to force a fresh database, before it was understood that the
 * `-- Version: N` comment on the DriveMainIndex CREATE statement already drives
 * [DatabaseManager.wipeAndRecreate]. The version bump is the *only* DB-reset mechanism;
 * renaming the file again would orphan every existing install's database and force a full
 * re-sync. To rebuild the schema, bump the version comment — never the filename.
 */
internal const val DB_FILE_NAME = "odin-2.db"

/**
 * The SQLite database file plus the sidecar files it can spawn — the single suffix list
 * shared by everything that enumerates DB files (recovery delete, size probe). `-journal`
 * (rollback mode), `-wal` (write-ahead log), `-shm` (WAL shared-memory index); any may
 * exist depending on which journal mode last touched the DB.
 */
internal val DB_FILE_SUFFIXES = listOf("", "-wal", "-shm", "-journal")

/**
 * Every on-disk file backing the database at [basePath] (the `.db` itself plus its
 * [DB_FILE_SUFFIXES] sidecars). The one place this list is built. Returns an empty list
 * when [basePath] is blank (platforms with no on-disk DB, e.g. wasmJs's in-memory sql.js).
 */
internal fun databaseFilePaths(basePath: String): List<String> =
    if (basePath.isEmpty()) emptyList() else DB_FILE_SUFFIXES.map { basePath + it }

/** All on-disk files backing this factory's database — see [databaseFilePaths]. */
internal fun DatabaseDriverFactory.databaseFiles(): List<String> =
    databaseFilePaths(dbFilePath())

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
 * Size in bytes of a single file at [path], or 0 if it doesn't exist. Per-platform for
 * the same reason as [deleteSqliteFileIfExists] (no shared filesystem API across wasmJs).
 */
internal expect fun fileSizeBytes(path: String): Long

/**
 * Delete the on-disk database file plus its WAL/SHM/journal siblings. The
 * suffix list lives in commonMain ([DB_FILE_SUFFIXES]) because it isn't
 * platform-specific — only [DatabaseDriverFactory.dbFilePath] is. Missing
 * files are treated as a no-op.
 */
internal fun DatabaseDriverFactory.deleteOnDiskFiles() {
    deleteDatabaseFiles(dbFilePath())
}

/**
 * Delete the database file at [dbFilePath] plus its [DB_FILE_SUFFIXES] siblings.
 * Extracted from [DatabaseDriverFactory.deleteOnDiskFiles] so unit tests can exercise the
 * suffix loop against a temp-directory path without instantiating a real factory (which
 * would target the user's actual data directory).
 */
internal fun deleteDatabaseFiles(dbFilePath: String) {
    databaseFilePaths(dbFilePath).forEach { deleteSqliteFileIfExists(it) }
}
