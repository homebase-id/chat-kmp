package id.homebase.api.sync.database

import app.cash.sqldelight.db.SqlDriver

/** The on-disk database file name used by every platform's [DatabaseDriverFactory]. */
internal const val DB_FILE_NAME = "odin-2.db"

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
