package id.homebase.api.sync.database

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PooledJdbcSqliteDriverTest {

    private val dir: File = Files.createTempDirectory("pooled-driver").toFile()
    private val driver = PooledJdbcSqliteDriver("jdbc:sqlite:${File(dir, "t.db").absolutePath}", tuningProperties())

    @AfterTest
    fun cleanup() {
        driver.close()
        dir.deleteRecursively()
    }

    @Test
    fun sequentialStatementsReuseOneConnection() {
        driver.execute(null, "CREATE TABLE t(x INTEGER)", 0)
        repeat(20) { driver.execute(null, "INSERT INTO t VALUES ($it)", 0) }
        assertEquals(20L, driver.count("SELECT count(*) FROM t"))
        assertEquals(1, driver.connectionsOpened)
    }

    @Test
    fun connectionStateSurvivesBetweenStatements() {
        // A TEMP table lives only as long as its connection.
        driver.execute(null, "CREATE TEMP TABLE scratch(x INTEGER)", 0)
        driver.execute(null, "INSERT INTO scratch VALUES (1)", 0)
        assertEquals(1L, driver.count("SELECT count(*) FROM scratch"))
    }

    @Test
    fun aSecondConnectionAlsoCarriesTheTuningPragmas() {
        driver.execute(null, "CREATE TABLE t(x INTEGER)", 0)
        driver.execute(null, "INSERT INTO t VALUES (1)", 0)
        // Hold the first connection open in a query so the pragma reads open a second one.
        driver.executeQuery(null, "SELECT x FROM t", { c ->
            c.next()
            assertEquals(5000L, driver.count("PRAGMA busy_timeout"))
            assertEquals(1L, driver.count("PRAGMA synchronous"))
            assertEquals(2L, driver.count("PRAGMA temp_store"))
            assertEquals(4_194_304L, driver.count("PRAGMA journal_size_limit"))
            QueryResult.Value(Unit)
        }, 0)
        assertEquals(2, driver.connectionsOpened)
        assertEquals("wal", driver.string("PRAGMA journal_mode"))
    }

    @Test
    fun inMemoryUrlsAreRejected() {
        assertFailsWith<IllegalArgumentException> { PooledJdbcSqliteDriver("jdbc:sqlite::memory:", tuningProperties()) }
        assertFailsWith<IllegalArgumentException> { PooledJdbcSqliteDriver("jdbc:sqlite:", tuningProperties()) }
    }

    @Test
    fun rolledBackTransactionLeavesAReusableConnection() {
        driver.execute(null, "CREATE TABLE t(x INTEGER)", 0)
        val transacter = object : app.cash.sqldelight.TransacterImpl(driver) {}
        assertFailsWith<IllegalStateException> {
            transacter.transaction {
                driver.execute(null, "INSERT INTO t VALUES (1)", 0)
                error("boom")
            }
        }
        assertEquals(0L, driver.count("SELECT count(*) FROM t"))
        transacter.transaction { driver.execute(null, "INSERT INTO t VALUES (2)", 0) }
        assertEquals(1L, driver.count("SELECT count(*) FROM t"))
        assertEquals(1, driver.connectionsOpened)
    }

    @Test
    fun concurrentReadersGetSeparateConnectionsThatAreThenKept() {
        driver.execute(null, "CREATE TABLE t(x INTEGER)", 0)
        driver.execute(null, "INSERT INTO t VALUES (1)", 0)
        val threads = 4
        val pool = Executors.newFixedThreadPool(threads)
        repeat(3) {
            val inside = CountDownLatch(threads)
            val done = CountDownLatch(threads)
            repeat(threads) {
                pool.execute {
                    driver.executeQuery(null, "SELECT x FROM t", { c ->
                        c.next()
                        inside.countDown()
                        // Hold every connection at once so the pool has to serve them in parallel.
                        inside.await(5, TimeUnit.SECONDS)
                        QueryResult.Value(Unit)
                    }, 0)
                    done.countDown()
                }
            }
            assertTrue(done.await(10, TimeUnit.SECONDS))
        }
        pool.shutdown()
        assertEquals(threads, driver.connectionsOpened)
    }

    private fun SqlDriver.count(sql: String): Long =
        executeQuery(null, sql, { c -> c.next(); QueryResult.Value(c.getLong(0)!!) }, 0).value

    private fun SqlDriver.string(sql: String): String =
        executeQuery(null, sql, { c -> c.next(); QueryResult.Value(c.getString(0)!!) }, 0).value
}
