package id.homebase.api.sync.database

import app.cash.sqldelight.Query
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlPreparedStatement
import app.cash.sqldelight.driver.jdbc.JdbcDriver
import app.cash.sqldelight.driver.jdbc.JdbcPreparedStatement
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.util.Properties
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * File-backed SQLite driver that keeps its connections open. A statement borrows an idle
 * connection (or opens one) and hands it back afterwards; a transaction keeps its connection
 * for its whole span. Concurrency is bounded by [DatabaseManager]'s dispatchers, so the pool
 * settles at about writer + readers; a connection returned to a full pool is closed.
 */
internal class PooledJdbcSqliteDriver(
    private val url: String,
    private val properties: Properties,
) : JdbcDriver() {
    init {
        // Each pooled connection to an in-memory URL would get its own empty database.
        require(!url.contains(":memory:") && !url.contains("mode=memory") && url != JdbcSqliteDriver.IN_MEMORY) {
            "PooledJdbcSqliteDriver needs a database file, got $url"
        }
    }

    private val idle = LinkedBlockingDeque<Connection>(1 + SQLITE_READER_CONNECTIONS)
    private val closed = AtomicBoolean(false)
    private val opened = AtomicInteger(0)
    private val listeners = linkedMapOf<String, MutableSet<Query.Listener>>()

    /** Connections opened over the driver's lifetime. */
    val connectionsOpened: Int get() = opened.get()

    override fun getConnection(): Connection {
        check(!closed.get()) { "Driver is closed" }
        while (true) {
            val connection = idle.pollFirst() ?: break
            if (!connection.isClosed) return connection
        }
        opened.incrementAndGet()
        return DriverManager.getConnection(url, properties)
    }

    override fun closeConnection(connection: Connection) {
        if (closed.get() || connection.isClosed || !idle.offerFirst(connection)) {
            connection.close()
            return
        }
        // close() may have drained the pool between the check above and the offer.
        if (closed.get()) drain()
    }

    // JdbcDriver.executeQuery leaks the statement when a binder throws, which a kept connection
    // would hold for the app's lifetime.
    override fun <R> executeQuery(
        identifier: Int?,
        sql: String,
        mapper: (SqlCursor) -> QueryResult<R>,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<R> {
        val (connection, onClose) = connectionAndClose()
        try {
            return connection.prepareStatement(sql).use { statement ->
                JdbcPreparedStatement(statement)
                    .apply { binders?.invoke(this) }
                    .executeQuery(mapper)
            }
        } finally {
            onClose()
        }
    }

    override fun Connection.beginTransaction() {
        prepareStatement("BEGIN TRANSACTION").use(PreparedStatement::execute)
    }

    override fun Connection.endTransaction() = finishTransaction("END TRANSACTION")

    override fun Connection.rollbackTransaction() = finishTransaction("ROLLBACK TRANSACTION")

    // A connection whose END/ROLLBACK failed may still be inside a transaction, so it is closed
    // instead of going back to the pool.
    private fun Connection.finishTransaction(sql: String) {
        try {
            prepareStatement(sql).use(PreparedStatement::execute)
        } catch (e: Exception) {
            close()
            throw e
        }
        closeConnection(this)
    }

    override fun close() {
        closed.set(true)
        drain()
    }

    private fun drain() {
        while (true) {
            (idle.pollFirst() ?: return).close()
        }
    }

    override fun addListener(vararg queryKeys: String, listener: Query.Listener) {
        synchronized(listeners) {
            queryKeys.forEach { listeners.getOrPut(it) { linkedSetOf() }.add(listener) }
        }
    }

    override fun removeListener(vararg queryKeys: String, listener: Query.Listener) {
        synchronized(listeners) {
            queryKeys.forEach { listeners[it]?.remove(listener) }
        }
    }

    override fun notifyListeners(vararg queryKeys: String) {
        val toNotify = linkedSetOf<Query.Listener>()
        synchronized(listeners) {
            queryKeys.forEach { listeners[it]?.let(toNotify::addAll) }
        }
        toNotify.forEach(Query.Listener::queryResultsChanged)
    }
}
