package id.homebase.core.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.sync.database.OdinDatabase

fun createTestDatabaseManager(): DatabaseManager {
    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    OdinDatabase.Schema.create(driver)
    return DatabaseManager({ driver })
}
