package id.homebase.api.sync.database

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.util.Properties
import kotlin.uuid.Uuid

actual fun createInMemoryDatabase(): SqlDriver {
    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    OdinDatabase.Schema.create(driver)
    return driver
}

// Two JDBC connections to the SAME shared-cache in-memory database — production's
// writer + read connection split, without a temp file. Each test gets a unique cache name
// so tests don't bleed into each other; the in-memory DB is freed when both connections
// close (DatabaseManager.close()). Schema is created once by DatabaseManager.init on the
// writer (so the providers must NOT pre-create it); the read connection sees it via the
// shared cache. busy_timeout guards the brief lock contention shared-cache can produce.
actual fun newTestDatabaseManager(): DatabaseManager {
    val url = "jdbc:sqlite:file:hbtest-${Uuid.random()}?mode=memory&cache=shared"
    val props = Properties().apply { setProperty("busy_timeout", "5000") }
    return DatabaseManager(
        driverProvider = { JdbcSqliteDriver(url, props) },
        readDriverProvider = { JdbcSqliteDriver(url, props) },
    )
}