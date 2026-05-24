package id.homebase.api.sync.database

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver

actual fun createInMemoryDatabase(): SqlDriver {
    return NativeSqliteDriver(
        schema = OdinDatabase.Schema,
        name = "test.db",
        onConfiguration = { config -> config.copy(name = null, inMemory = true) })
}

// SQLiter in-memory databases are per-connection — a second NativeSqliteDriver would be a
// separate empty DB, so we can't share an in-memory DB across two connections here. Fall
// back to the single-connection manager (reads use the writer lane, the prior behavior).
// The dual-connection path is covered on JVM by ReadWriteConcurrencyTest + the shared-cache
// actuals.
actual fun newTestDatabaseManager(): DatabaseManager =
    DatabaseManager({ createInMemoryDatabase() })
