package id.homebase.api.sync.database

import app.cash.sqldelight.db.SqlDriver

/** The on-disk database file name used by every platform's [DatabaseDriverFactory]. */
internal const val DB_FILE_NAME = "odin-2.db"

@Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
expect class DatabaseDriverFactory {
    fun createDriver(passphrase: String? = null): SqlDriver

    /**
     * Delete the on-disk database file and its WAL/SHM/journal siblings.
     * Used by [DatabaseManager.initializeWithRecovery] when the driver
     * fails to open (corrupted file, undecryptable with the stored key,
     * schema mismatch). Implementations should treat a missing file as a
     * no-op rather than an error.
     */
    fun deleteOnDiskFiles()
}
