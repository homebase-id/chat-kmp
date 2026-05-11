package id.homebase.api.sync.database

import app.cash.sqldelight.db.SqlDriver
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

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
     */
    fun dbFilePath(): String
}

/**
 * Delete the on-disk database file plus its WAL/SHM/journal siblings. The
 * loop and suffix list live in commonMain because they aren't
 * platform-specific — only [DatabaseDriverFactory.dbFilePath] is. Missing
 * files are treated as a no-op (`mustExist = false`).
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
    for (suffix in listOf("", "-journal", "-wal", "-shm")) {
        SystemFileSystem.delete(Path(dbFilePath + suffix), mustExist = false)
    }
}
