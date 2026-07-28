package id.homebase.core.feed

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement

/**
 * A [SqlDriver] that refuses `INSERT INTO Outbox` while [failOutboxInserts] is set, so a test can
 * reach a service's enqueue-failure / rollback branch (`EnqueueResult.Failed`). Every other
 * statement — including the optimistic DriveMainIndex writes and all reads — passes through, so
 * the local state before and after the failed enqueue stays inspectable.
 *
 * Pass it to [FeedTestEnv] via its `wrapDriver` hook, then flip the flag after seeding.
 */
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
