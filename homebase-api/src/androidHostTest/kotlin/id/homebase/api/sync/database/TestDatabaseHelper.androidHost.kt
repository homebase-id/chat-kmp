package id.homebase.api.sync.database

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver

// Android host-test runs on JVM with android.jar stubs, so the JDBC SQLite
// driver (pure Java) is the right fit — AndroidSqliteDriver would throw
// "Stub!" without a Robolectric-style runtime.
actual fun createInMemoryDatabase(): SqlDriver {
    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    OdinDatabase.Schema.create(driver)
    return driver
}

// Raw driver, no schema applied — the test stages its own DDL.
actual fun createRawInMemoryDriver(): SqlDriver =
    JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
