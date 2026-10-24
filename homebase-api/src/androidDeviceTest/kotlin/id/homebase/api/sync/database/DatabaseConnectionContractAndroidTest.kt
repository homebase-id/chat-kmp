package id.homebase.api.sync.database

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.cash.sqldelight.TransacterImpl
import app.cash.sqldelight.db.QueryResult
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Android copy of DatabaseConnectionContractTest (jvmAndNativeTest): instrumented tests can't
 * depend on that source set. Android's driver frees a reader's connection before mapping, so
 * this proves reads run beside an open write transaction, not that four run at once.
 */
@RunWith(AndroidJUnit4::class)
class DatabaseConnectionContractAndroidTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val driver = run {
        context.deleteDatabase(CONTRACT_DB)
        openAndroidSqliteDriver(context, CONTRACT_DB, "contract-test-key")
    }

    @After
    fun cleanup() {
        driver.close()
        context.deleteDatabase(CONTRACT_DB)
    }

    @Test
    fun readersRunWhileAWriteTransactionIsOpen() {
        val transacter = object : TransacterImpl(driver) {}
        driver.execute(null, "CREATE TABLE contract(x INTEGER)", 0)
        driver.execute(null, "INSERT INTO contract VALUES (1)", 0)
        val threads = Executors.newFixedThreadPool(SQLITE_READER_CONNECTIONS + 1)
        try {
            val writerInside = CountDownLatch(1)
            val release = CountDownLatch(1)
            val writer = threads.submit {
                transacter.transaction {
                    driver.execute(null, "INSERT INTO contract VALUES (2)", 0)
                    writerInside.countDown()
                    release.await(30, TimeUnit.SECONDS)
                }
            }
            assertTrue(writerInside.await(10, TimeUnit.SECONDS))

            val readers = List(SQLITE_READER_CONNECTIONS) {
                threads.submit<List<Long?>> {
                    driver.executeQuery(null, READER_SQL, { c ->
                        c.next()
                        QueryResult.Value(listOf(c.getLong(0), c.getLong(1), c.getLong(2), c.getLong(3)))
                    }, 0).value
                }
            }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
            val seen = try {
                readers.map { it.get(deadline - System.nanoTime(), TimeUnit.NANOSECONDS) }
            } catch (_: TimeoutException) {
                null
            } finally {
                release.countDown()
            }
            writer.get(30, TimeUnit.SECONDS)

            assertNotNull(seen, "reads waited for the open write transaction")
            seen.forEach { (count, busyTimeout, synchronous, tempStore) ->
                assertEquals(1L, count, "a reader saw the uncommitted write")
                assertConnectionPragmas(busyTimeout, synchronous, tempStore)
            }

            val (count, busyTimeout, synchronous, tempStore, journalSizeLimit) = transacter.transactionWithResult {
                driver.executeQuery(null, WRITER_SQL, { c ->
                    c.next()
                    QueryResult.Value(listOf(c.getLong(0), c.getLong(1), c.getLong(2), c.getLong(3), c.getLong(4)))
                }, 0).value
            }
            assertEquals(2L, count)
            assertConnectionPragmas(busyTimeout, synchronous, tempStore)
            assertEquals(SQLITE_CONNECTION_PRAGMAS.getValue("journal_size_limit").toLong(), journalSizeLimit)
            assertEquals(
                SQLITE_JOURNAL_MODE.lowercase(),
                driver.executeQuery(null, "PRAGMA journal_mode", { c -> c.next(); QueryResult.Value(c.getString(0)) }, 0).value,
            )
        } finally {
            threads.shutdownNow()
        }
    }

    private fun assertConnectionPragmas(busyTimeout: Long?, synchronous: Long?, tempStore: Long?) {
        assertEquals(SQLITE_CONNECTION_PRAGMAS.getValue("busy_timeout").toLong(), busyTimeout, "busy_timeout")
        assertEquals(1L, synchronous, "synchronous should be NORMAL")
        assertEquals(2L, tempStore, "temp_store should be MEMORY")
    }

    private companion object {
        const val CONTRACT_DB = "contract-test.db"
        const val READER_SQL = "SELECT (SELECT count(*) FROM contract), (SELECT * FROM pragma_busy_timeout), " +
            "(SELECT * FROM pragma_synchronous), (SELECT * FROM pragma_temp_store)"
        const val WRITER_SQL = "SELECT (SELECT count(*) FROM contract), (SELECT * FROM pragma_busy_timeout), " +
            "(SELECT * FROM pragma_synchronous), (SELECT * FROM pragma_temp_store), (SELECT * FROM pragma_journal_size_limit)"
    }
}
