package id.homebase.api.sync.database

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

/**
 * Proves the read path is decoupled from the single write dispatcher and that the
 * new "reads run off-writer" model doesn't crash SQLite / the driver.
 *
 * Both tests use real dispatchers + real threads (not `runTest` virtual time)
 * against the existing single in-memory [JdbcSqliteDriver] — the same single-driver
 * substrate production uses. The in-memory JDBC URL maps to SQLDelight's
 * `StaticConnectionManager` (one shared connection), so reads here serialize at the
 * driver lock rather than running truly concurrently — that's fine: this exercises
 * the *coroutine-level* decoupling and the safety of concurrent access to the one
 * driver. The mobile concurrency win (iOS reader pool / Android WAL pool) is
 * validated on-device, not here.
 */
class DatabaseReadConcurrencyTest {

    private fun newDbm(): DatabaseManager {
        // DatabaseManager.init creates the schema (CREATE TABLE IF NOT EXISTS) and
        // audits pragmas, so the driver needs no pre-setup.
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        return DatabaseManager({ driver })
    }

    private suspend fun keyValueCount(dbm: DatabaseManager): Long =
        dbm.executeReadQuery(
            identifier = null,
            sql = "SELECT COUNT(*) FROM KeyValue",
            mapper = { cursor ->
                cursor.next()
                QueryResult.Value(cursor.getLong(0) ?: 0L)
            },
            parameters = 0,
        ).value

    /**
     * A read issued while the single write dispatcher slot is held must NOT queue
     * behind it: it runs on the separate read dispatcher and completes while the
     * write is still parked, with `queueWait ~0`. Before this change the read ran
     * `withContext(writeDispatcher)` and would block until the write released.
     */
    @Test
    fun readDoesNotQueueBehindHeldWriteDispatcher() = runBlocking {
        val dbm = newDbm()

        val writeStarted = CountDownLatch(1)
        val releaseWrite = CountDownLatch(1)

        // Occupy the single write dispatcher slot. The block is non-suspend, so we
        // block the writer thread synchronously — what a long write would do to the
        // slot. No SQL runs inside, so the shared connection itself stays free.
        val writeJob = launch(Dispatchers.Default) {
            dbm.withWrite {
                writeStarted.countDown()
                releaseWrite.await()
            }
        }
        assertTrue(writeStarted.await(5, TimeUnit.SECONDS), "write never acquired the slot")

        // Issue a read while the writer slot is held; it must return before release.
        val count = withTimeout(5.seconds) { keyValueCount(dbm) }
        assertEquals(0L, count)

        assertEquals(
            1L, releaseWrite.count,
            "read only completed after the write was released — it queued behind the writer",
        )

        val timing = dbm.lastReadTiming
        assertNotNull(timing, "no read timing recorded")
        assertTrue(
            timing.queueWaitMs < 100,
            "read queued behind the write dispatcher: queueWait=${timing.queueWaitMs}ms",
        )

        releaseWrite.countDown()
        writeJob.join()
    }

    /**
     * Many concurrent writers (serialized through the write dispatcher) racing many
     * concurrent readers (on the read dispatcher) against the one driver must not
     * throw or corrupt: the final row count equals the number of distinct keys
     * written. This is the proof the new architecture is safe on the single driver.
     */
    @Test
    fun concurrentReadsAndWritesDoNotCrash() = runBlocking {
        val dbm = newDbm()

        val writerCount = 50
        val readerCount = 200

        val writers = (0 until writerCount).map { i ->
            launch(Dispatchers.Default) {
                val key = Uuid.fromLongs(0L, i.toLong()) // distinct per writer
                dbm.withWriteValue { db ->
                    db.keyValueQueries.upsertValue(key, byteArrayOf(i.toByte()))
                }
            }
        }
        val readers = (0 until readerCount).map {
            launch(Dispatchers.Default) {
                keyValueCount(dbm) // result unused; we're proving it doesn't throw
            }
        }

        // Any exception in a child cancels the scope and fails the test here.
        (writers + readers).joinAll()

        assertEquals(writerCount.toLong(), keyValueCount(dbm))
    }
}
