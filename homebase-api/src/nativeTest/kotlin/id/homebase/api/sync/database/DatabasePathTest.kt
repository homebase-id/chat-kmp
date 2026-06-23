@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package id.homebase.api.sync.database

import app.cash.sqldelight.driver.native.NativeSqliteDriver
import platform.Foundation.NSFileManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Pins the iOS DB path: [iosDatabasesDir] (used by `dbFilePath()`, the recovery delete and
 * the size probe) must match where sqliter — NativeSqliteDriver's engine — actually stores a
 * database opened by bare `name`. A mismatch silently breaks recovery (`deleteOnDiskFiles`
 * deletes the wrong path) and iCloud backup-exclusion, which is exactly what made the
 * `no such column: fileState` crash fatal on iOS instead of self-healing.
 *
 * Fails if a future sqliter changes its default directory away from
 * `NSApplicationSupportDirectory/databases`.
 */
class DatabasePathTest {

    private val testDbName = "odin-pathtest.db"
    private val expectedPath = "${iosDatabasesDir()}/$testDbName"

    @AfterTest
    fun cleanup() {
        deleteDatabaseFiles(expectedPath)
    }

    @Test
    fun dbPathHelperMatchesWhereSqliterCreatesTheFile() {
        // Default basePath → sqliter resolves the file to its own default location.
        val driver = NativeSqliteDriver(schema = OdinDatabase.Schema, name = testDbName)
        try {
            // NativeSqliteDriver opens the connection (and creates the on-disk file) lazily on
            // first use, not in the constructor — force a write so the file actually exists.
            driver.execute(null, "CREATE TABLE IF NOT EXISTS _probe(x INTEGER)", 0)
            assertTrue(
                NSFileManager.defaultManager.fileExistsAtPath(expectedPath),
                "iosDatabasesDir() must point at sqliter's storage dir; " +
                    "expected the opened DB at $expectedPath",
            )
        } finally {
            driver.close()
        }
    }
}
