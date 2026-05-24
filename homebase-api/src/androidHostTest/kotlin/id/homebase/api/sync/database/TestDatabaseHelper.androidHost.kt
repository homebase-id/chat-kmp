package id.homebase.api.sync.database

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.util.Properties
import kotlin.uuid.Uuid

// Android host-test runs on JVM with android.jar stubs, so the JDBC SQLite
// driver (pure Java) is the right fit — AndroidSqliteDriver would throw
// "Stub!" without a Robolectric-style runtime.
actual fun createInMemoryDatabase(): SqlDriver {
    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    OdinDatabase.Schema.create(driver)
    return driver
}

// See the JVM actual — two connections to one shared-cache in-memory DB (production's
// writer + read split).
actual fun newTestDatabaseManager(): DatabaseManager {
    val url = "jdbc:sqlite:file:hbtest-${Uuid.random()}?mode=memory&cache=shared"
    val props = Properties().apply { setProperty("busy_timeout", "5000") }
    return DatabaseManager(
        driverProvider = { JdbcSqliteDriver(url, props) },
        readDriverProvider = { JdbcSqliteDriver(url, props) },
    )
}
