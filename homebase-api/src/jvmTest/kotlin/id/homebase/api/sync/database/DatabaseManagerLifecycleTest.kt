package id.homebase.api.sync.database

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DatabaseManagerLifecycleTest {

    private val dir: File = Files.createTempDirectory("dbm-lifecycle").toFile()
    private val dbFile = File(dir, "t.db")

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    @Test
    fun aFailedOpenClosesTheDriver() {
        val driver = FailingCreateDriver(openSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}"))
        assertFailsWith<IllegalStateException> { DatabaseManager({ driver }) }
        assertTrue(driver.closed)
    }

    @Test
    fun vacuumLeavesAnEmptyWal() = runTest {
        val dbm = DatabaseManager({ openSqliteDriver("jdbc:sqlite:${dbFile.absolutePath}") })
        dbm.driver.execute(null, "CREATE TABLE junk(b BLOB)", 0)
        repeat(4) { dbm.driver.execute(null, "INSERT INTO junk VALUES (randomblob(1000000))", 0) }
        dbm.driver.execute(null, "DELETE FROM junk", 0)
        dbm.vacuum()
        assertEquals(0L, File(dbFile.path + "-wal").length())
        dbm.close()
    }

    private class FailingCreateDriver(private val delegate: SqlDriver) : SqlDriver by delegate {
        var closed = false

        override fun execute(
            identifier: Int?,
            sql: String,
            parameters: Int,
            binders: (SqlPreparedStatement.() -> Unit)?,
        ): QueryResult<Long> {
            check(!sql.contains("CREATE TABLE")) { "simulated schema failure" }
            return delegate.execute(identifier, sql, parameters, binders)
        }

        override fun close() {
            closed = true
            delegate.close()
        }
    }
}
