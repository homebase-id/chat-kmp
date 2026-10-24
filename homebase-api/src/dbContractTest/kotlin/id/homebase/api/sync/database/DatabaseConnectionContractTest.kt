@file:OptIn(DelicateCoroutinesApi::class, ExperimentalAtomicApi::class)

package id.homebase.api.sync.database

import app.cash.sqldelight.TransacterImpl
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.newFixedThreadPoolContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * The real platform driver on a scratch database, from openContractDriver() in jvmTest, nativeTest
 * and androidDeviceTest; this directory is compiled into each of them.
 */
internal class ContractDriver(
    val driver: SqlDriver,
    // Android copies a query's rows into a cursor window and frees the connection before mapping.
    val holdsConnectionWhileMapping: Boolean,
    val cleanup: () -> Unit,
)

/** The [DatabaseDriverFactory.createDriver] contract on the real driver; wasmJs is exempt. */
class DatabaseConnectionContractTest {

    @Test
    fun readersRunWhileAWriteTransactionIsOpen() = runBlocking {
        val db = openContractDriver()
        val threads = newFixedThreadPoolContext(SQLITE_READER_CONNECTIONS + 1, "db-contract")
        try {
            val driver = db.driver
            val transacter = object : TransacterImpl(driver) {}
            driver.execute(null, "CREATE TABLE contract(x INTEGER)", 0)
            driver.execute(null, "INSERT INTO contract VALUES (1)", 0)

            val writerInside = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val writer = launch(threads) {
                transacter.transaction {
                    driver.execute(null, "INSERT INTO contract VALUES (2)", 0)
                    writerInside.complete(Unit)
                    runBlocking { withTimeout(30.seconds) { release.await() } }
                }
            }
            writerInside.await()

            val inFlight = AtomicInt(0)
            val readers = List(SQLITE_READER_CONNECTIONS) {
                async(threads) {
                    driver.executeQuery(null, READER_SQL, { c ->
                        c.next()
                        val view = listOf(c.getLong(0), c.getLong(1), c.getLong(2), c.getLong(3))
                        if (db.holdsConnectionWhileMapping) awaitAllInFlight(inFlight)
                        QueryResult.Value(view)
                    }, 0).value
                }
            }
            val seen = try {
                withTimeoutOrNull(15.seconds) { readers.awaitAll() }
            } finally {
                release.complete(Unit)
                writer.join()
            }
            readers.forEach { runCatching { it.await() } }

            assertNotNull(seen, "reads waited for the open write transaction")
            seen.forEach { (count, busyTimeout, synchronous, tempStore) ->
                assertEquals(1L, count, "a reader saw the uncommitted write")
                assertConnectionPragmas(busyTimeout, synchronous, tempStore)
            }

            val writerView = transacter.transactionWithResult {
                driver.executeQuery(null, WRITER_SQL, { c ->
                    c.next()
                    QueryResult.Value(listOf(c.getLong(0), c.getLong(1), c.getLong(2), c.getLong(3), c.getLong(4)))
                }, 0).value
            }
            val (count, busyTimeout, synchronous, tempStore, journalSizeLimit) = writerView
            assertEquals(2L, count)
            assertConnectionPragmas(busyTimeout, synchronous, tempStore)
            assertEquals(SQLITE_WRITER_JOURNAL_SIZE_LIMIT.toLong(), journalSizeLimit)
            assertEquals(
                SQLITE_JOURNAL_MODE.lowercase(),
                driver.executeQuery(null, "PRAGMA journal_mode", { c -> c.next(); QueryResult.Value(c.getString(0)) }, 0).value,
            )
        } finally {
            threads.close()
            db.cleanup()
        }
    }

    // Every reader holds its connection until all of them are mapping, so they ran at once.
    private fun awaitAllInFlight(inFlight: AtomicInt) {
        inFlight.incrementAndFetch()
        val deadline = TimeSource.Monotonic.markNow() + 10.seconds
        runBlocking {
            while (inFlight.load() < SQLITE_READER_CONNECTIONS) {
                check(deadline.hasNotPassedNow()) {
                    "only ${inFlight.load()} of $SQLITE_READER_CONNECTIONS reads ran at once"
                }
                delay(5)
            }
        }
    }

    private fun assertConnectionPragmas(busyTimeout: Long?, synchronous: Long?, tempStore: Long?) {
        assertEquals(SQLITE_CONNECTION_PRAGMAS.getValue("busy_timeout").toLong(), busyTimeout, "busy_timeout")
        assertEquals(1L, synchronous, "synchronous should be NORMAL")
        assertEquals(2L, tempStore, "temp_store should be MEMORY")
    }

    private companion object {
        const val READER_SQL = "SELECT (SELECT count(*) FROM contract), (SELECT * FROM pragma_busy_timeout), " +
            "(SELECT * FROM pragma_synchronous), (SELECT * FROM pragma_temp_store)"
        const val WRITER_SQL = "SELECT (SELECT count(*) FROM contract), (SELECT * FROM pragma_busy_timeout), " +
            "(SELECT * FROM pragma_synchronous), (SELECT * FROM pragma_temp_store), (SELECT * FROM pragma_journal_size_limit)"
    }
}
