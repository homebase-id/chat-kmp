package id.homebase.api.sync.database

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver

actual fun createInMemoryDatabase(): SqlDriver {
    return NativeSqliteDriver(
        schema = OdinDatabase.Schema,
        name = "test.db",
        onConfiguration = { config -> config.copy(name = null, inMemory = true) })
}
