package id.homebase.api.sync.database

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import co.touchlab.kermit.Logger
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Adapters as top-level constants (stateless, shared)
private val appNotificationsAdapter = AppNotifications.Adapter(
    identityIdAdapter = UuidAdapter,
    notificationIdAdapter = UuidAdapter
)
private val chatReadCountAdapter = ChatReadCount.Adapter(
    groupIdAdapter = UuidAdapter
)
private val driveMainIndexAdapter = DriveMainIndex.Adapter(
    identityIdAdapter = UuidAdapter,
    driveIdAdapter = UuidAdapter,
    fileIdAdapter = UuidAdapter,
    globalTransitIdAdapter = UuidAdapter,
    groupIdAdapter = UuidAdapter,
    uniqueIdAdapter = UuidAdapter
)
private val driveTagIndexAdapter = DriveTagIndex.Adapter(
    identityIdAdapter = UuidAdapter,
    driveIdAdapter = UuidAdapter,
    fileIdAdapter = UuidAdapter,
    tagIdAdapter = UuidAdapter
)
private val driveLocalTagIndexAdapter = DriveLocalTagIndex.Adapter(
    identityIdAdapter = UuidAdapter,
    driveIdAdapter = UuidAdapter,
    fileIdAdapter = UuidAdapter,
    tagIdAdapter = UuidAdapter
)
private val keyValueAdapter = KeyValue.Adapter(
    keyAdapter = UuidAdapter
)
private val outboxAdapter = Outbox.Adapter(
    driveIdAdapter = UuidAdapter,
    uniqueIdAdapter = UuidAdapter,
    dependencyUniqueIdAdapter = UuidAdapter
)
private val connectionCacheAdapter = ConnectionCache.Adapter(
    identityIdAdapter = UuidAdapter
)

class DatabaseManager(
    driverProvider: () -> SqlDriver,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1)
) : AutoCloseable {
    private val logger = Logger.withTag("DatabaseManager")
    private var database: OdinDatabase
    internal var driver: SqlDriver = driverProvider()

    init {
        OdinDatabase.Schema.create(driver) // Create the tables if they are missing
        database = OdinDatabase(
            driver,
            appNotificationsAdapter,
            chatReadCountAdapter,
            connectionCacheAdapter,
            driveLocalTagIndexAdapter,
            driveMainIndexAdapter,
            driveTagIndexAdapter,
            keyValueAdapter,
            outboxAdapter
        )
        logger.i { "Database initialized" }
    }

    companion object {
        private const val DATABASE_VERSION =
            1  // Increase to wipe the database and rebuild all tables
        private lateinit var instance: DatabaseManager
        val appDb: DatabaseManager get() = instance

        // Single source of truth for every table in OdinDatabase. If a new table is
        // added to the schema, add it here or wipeAndRecreate() will silently skip it
        // on logout — exactly the class of bug that leaks Outbox rows across sessions.
        internal val TABLE_NAMES = listOf(
            "AppNotifications",
            "ChatReadCount",
            "ConnectionCache",
            "DriveLocalTagIndex",
            "DriveMainIndex",
            "DriveTagIndex",
            "KeyValue",
            "Outbox"
        )

        suspend fun initialize(driverProvider: () -> SqlDriver) {
            if (::instance.isInitialized) throw IllegalStateException("Already initialized")

            instance = DatabaseManager(driverProvider)

            val version = instance.driveMainIndex.getSchemaVersion()

            if (version < DATABASE_VERSION) {
                Logger.withTag("DatabaseManager")
                    .i { "Schema version $version < $DATABASE_VERSION — wiping tables" }
                instance.wipeAndRecreate()
            }
        }
    }

    val appNotifications: AppNotificationsWrapper by lazy {
        AppNotificationsWrapper(
            driver,
            appNotificationsAdapter,
            this
        )
    }
    val chatReadCount: ChatReadCountWrapper by lazy {
        ChatReadCountWrapper(driver, chatReadCountAdapter, driveMainIndexAdapter, this)
    }
    val driveMainIndex: DriveMainIndexWrapper by lazy {
        DriveMainIndexWrapper(
            driver,
            driveMainIndexAdapter,
            this
        )
    }
    val driveLocalTagIndex: DriveLocalTagIndexWrapper by lazy {
        DriveLocalTagIndexWrapper(
            driver,
            driveLocalTagIndexAdapter,
            this
        )
    }
    val driveTagIndex: DriveTagIndexWrapper by lazy {
        DriveTagIndexWrapper(
            driver,
            driveTagIndexAdapter,
            this
        )
    }

    // Lazy wrappers
    val keyValue: KeyValueWrapper by lazy {
        KeyValueWrapper(driver, keyValueAdapter, this)
    }
    val outbox: OutboxWrapper by lazy {
        OutboxWrapper(driver, outboxAdapter, this)
    }
    val connectionCache: ConnectionCacheWrapper by lazy {
        ConnectionCacheWrapper(driver, connectionCacheAdapter, this)
    }

    suspend fun <R> executeReadQuery(
        identifier: Int?,
        sql: String,
        mapper: (SqlCursor) -> QueryResult<R>,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)? = null
    ): QueryResult<R> = withContext(dispatcher) {
        try {
            driver.executeQuery(identifier, sql, mapper, parameters, binders)
        } catch (e: Exception) {
            logger.e { "executeReadQuery failed: ${e.message}\nSQL: $sql\nStack: ${e.stackTraceToString()}" }
            throw e  // Rethrow if you want the caller to handle, or return a fallback QueryResult
        }
    }

    suspend fun withWriteTransaction(block: (OdinDatabase) -> Unit) {
        val queueStart = TimeSource.Monotonic.markNow()
        withContext(dispatcher) {
            val wait = queueStart.elapsedNow()
            val execStart = TimeSource.Monotonic.markNow()
            database.transaction { block(database) }
            Logger.i(tag = "DbWrite") {
                "withWriteTransaction wait=$wait exec=${execStart.elapsedNow()}"
            }
        }
    }

    suspend fun withWrite(block: (OdinDatabase) -> Unit) {
        val queueStart = TimeSource.Monotonic.markNow()
        withContext(dispatcher) {
            val wait = queueStart.elapsedNow()
            val execStart = TimeSource.Monotonic.markNow()
            block(database)
            Logger.i(tag = "DbWrite") {
                "withWrite wait=$wait exec=${execStart.elapsedNow()}"
            }
        }
    }

    suspend fun <R> withWriteValue(block: (OdinDatabase) -> R): R {
        val queueStart = TimeSource.Monotonic.markNow()
        return withContext(dispatcher) {
            val wait = queueStart.elapsedNow()
            val execStart = TimeSource.Monotonic.markNow()
            val result = block(database)
            Logger.i(tag = "DbWrite") {
                "withWriteValue wait=$wait exec=${execStart.elapsedNow()}"
            }
            result
        }
    }

    // Nuke every table and rebuild the schema from scratch. Used on logout (via
    // DriveSyncManager.clearStorage) and on schema-version bump (via initialize).
    //
    // DROP TABLE is used instead of DELETE FROM because DROP is an unforgeable
    // guarantee: after it returns, the rows cannot survive an open transaction,
    // a stale cache, or a stray driver reference. DELETE has bitten us — the
    // Outbox has been observed to keep rows across logout/login with their retry
    // counters intact, meaning some deleteAll() was either racing another writer
    // or hitting a different driver instance. DROP + CREATE + VACUUM on a single
    // driver, inside the one-at-a-time dbDispatcher, removes all of those loopholes.
    //
    // Two verification probes run alongside the wipe and log an error if they fire:
    //   1. After DROP: counting rows on the table must throw "no such table". If
    //      the SELECT succeeds, DROP didn't take effect (wrong driver, or something
    //      caught the exception silently).
    //   2. After CREATE: the row count must be zero. If it's not, something wrote
    //      to the freshly recreated table before we finished — usually a caller
    //      still running with stale credentials.
    suspend fun wipeAndRecreate() = withContext(dispatcher) {
        val log = Logger.withTag("DatabaseManager")

        TABLE_NAMES.forEach { table ->
            driver.execute(null, "DROP TABLE IF EXISTS $table;", 0)
        }

        // Probe 1: after DROP every SELECT must throw.
        TABLE_NAMES.forEach { table ->
            val stillThere = runCatching {
                driver.executeQuery(
                    identifier = null,
                    sql = "SELECT COUNT(*) FROM $table",
                    mapper = { cursor ->
                        cursor.next()
                        QueryResult.Value(cursor.getLong(0) ?: 0L)
                    },
                    parameters = 0,
                ).value
            }.getOrNull()
            if (stillThere != null) {
                log.e { "wipeAndRecreate: table '$table' still queryable after DROP (rows=$stillThere) — wipe did not take effect" }
            }
        }

        OdinDatabase.Schema.create(driver)

        // Probe 2: after CREATE every table must be empty.
        TABLE_NAMES.forEach { table ->
            val count = runCatching {
                driver.executeQuery(
                    identifier = null,
                    sql = "SELECT COUNT(*) FROM $table",
                    mapper = { cursor ->
                        cursor.next()
                        QueryResult.Value(cursor.getLong(0) ?: 0L)
                    },
                    parameters = 0,
                ).value
            }.getOrElse { e ->
                log.e(e) { "wipeAndRecreate: could not count '$table' after CREATE" }
                -1L
            }
            if (count > 0L) {
                log.e { "wipeAndRecreate: table '$table' has $count rows after wipe — something re-inserted mid-wipe" }
            }
        }

        // Reclaim the pages freed by DROP. VACUUM must run outside any transaction;
        // dbDispatcher has the single-writer slot so we're safe here.
        driver.execute(identifier = null, sql = "VACUUM", parameters = 0)

        log.i { "wipeAndRecreate: completed (${TABLE_NAMES.size} tables)" }
    }

    // Reclaim space released by DELETE/DROP without nuking schema. Runs on the
    // single-writer [dispatcher] so it cannot race with other queries. Used by
    // the Defragmenter screen as its finale.
    suspend fun vacuum() = withContext(dispatcher) {
        driver.execute(identifier = null, sql = "VACUUM", parameters = 0)
    }

    override fun close() {
        driver.close()
        logger.i { "Database closed" }
    }
}
