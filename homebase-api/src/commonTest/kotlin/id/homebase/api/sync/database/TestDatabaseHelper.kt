package id.homebase.api.sync.database

import app.cash.sqldelight.db.SqlDriver

/**
 * Creates an in-memory test database
 * Platform-specific implementations provide appropriate SQLDelight drivers
 */
expect fun createInMemoryDatabase(): SqlDriver

/**
 * Build a [DatabaseManager] configured the way PRODUCTION runs it — a single writer
 * connection plus a SEPARATE read connection over the same database — so the test suite
 * exercises the dual-connection read path (cross-connection read-your-writes, read routing)
 * rather than the single-connection fallback.
 *
 * On JVM/androidHost this uses a SQLite shared-cache in-memory database so two connections
 * see the same data without a temp file (auto-freed when the connections close). On native,
 * where in-memory databases can't be shared across connections, it falls back to the
 * single-connection manager (the WAL/concurrency path is covered by the file-based
 * `ReadWriteConcurrencyTest` on JVM).
 *
 * Use this instead of `DatabaseManager({ createInMemoryDatabase() })` in tests.
 */
expect fun newTestDatabaseManager(): DatabaseManager
