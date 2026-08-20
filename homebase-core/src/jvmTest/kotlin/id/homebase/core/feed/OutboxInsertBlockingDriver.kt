package id.homebase.core.feed

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement

// Everything but `INSERT INTO Outbox` passes through, so local state before and after the failed
// enqueue stays inspectable. Wire it in via FeedTestEnv's `wrapDriver` hook.
class OutboxInsertBlockingDriver(private val delegate: SqlDriver) : SqlDriver by delegate {

    var failOutboxInserts: Boolean = false

    override fun execute(
        identifier: Int?,
        sql: String,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<Long> {
        if (failOutboxInserts && sql.contains("INSERT INTO Outbox")) {
            throw IllegalStateException("outbox insert refused by OutboxInsertBlockingDriver")
        }
        return delegate.execute(identifier, sql, parameters, binders)
    }
}
