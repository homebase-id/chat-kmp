package id.homebase.api.sync.database

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.native.NativeSqliteDriver

actual fun createInMemoryDatabase(): SqlDriver {
    return NativeSqliteDriver(
        schema = OdinDatabase.Schema,
        name = "test.db",
        onConfiguration = { config -> config.copy(name = null, inMemory = true) })
}

// NativeSqliteDriver requires a schema argument, but for a *raw* driver we want
// no tables created — so hand it a no-op schema. The test then stages its own DDL.
private object EmptySchema : SqlSchema<QueryResult.Value<Unit>> {
    override val version: Long = 1
    override fun create(driver: SqlDriver): QueryResult.Value<Unit> = QueryResult.Unit
    override fun migrate(
        driver: SqlDriver,
        oldVersion: Long,
        newVersion: Long,
        vararg callbacks: app.cash.sqldelight.db.AfterVersion,
    ): QueryResult.Value<Unit> = QueryResult.Unit
}

actual fun createRawInMemoryDriver(): SqlDriver =
    NativeSqliteDriver(
        schema = EmptySchema,
        name = "rawtest.db",
        onConfiguration = { config -> config.copy(name = null, inMemory = true) })
