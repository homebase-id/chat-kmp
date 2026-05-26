package id.homebase.core.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.sync.database.OdinDatabase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher

/**
 * Builds an in-memory [DatabaseManager] whose write AND read lanes run on the test scheduler.
 *
 * The read lane (DatabaseManager.readValue / executeReadQuery) normally hops to the real
 * [id.homebase.api.coroutines.ioDispatcher] (Dispatchers.IO on JVM). Once the single-row
 * DriveMainIndex reads became `suspend` + read-lane-dispatched, a read issued *inside* an
 * observer (e.g. DriveRegistry's BatchReceived collector calling loadDrives →
 * selectHomebaseFileByUnique) escaped the test scheduler, so `advanceUntilIdle()` no longer
 * waited for it and the emission assertions raced. Injecting an [UnconfinedTestDispatcher]
 * backed by this scope's [TestScope.testScheduler] for both lanes makes `withContext` run the
 * read/write inline (eagerly), matching the old synchronous reads so observer-driven reads
 * complete in step with the collector instead of escaping to a real thread.
 *
 * Must be called from within `runTest` (it's a [TestScope] extension).
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun TestScope.createTestDatabaseManager(): DatabaseManager {
    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    OdinDatabase.Schema.create(driver)
    val testDispatcher = UnconfinedTestDispatcher(testScheduler)
    return DatabaseManager(
        { driver },
        dispatcher = testDispatcher,
        readDispatcher = testDispatcher,
    )
}
