package id.homebase.api.sync.database

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlPreparedStatement
import co.touchlab.kermit.Logger
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

class DatabaseManager(driverProvider: () -> SqlDriver) : AutoCloseable {
    private val logger = Logger.withTag("DatabaseManager")
    private var database: OdinDatabase
    internal var driver: SqlDriver

    //    private val dbDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val dbDispatcher = Dispatchers.Default.limitedParallelism(1)

    init {
        driver = driverProvider()
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

        suspend fun initialize(driverProvider: () -> SqlDriver) {
            if (::instance.isInitialized) throw IllegalStateException("Already initialized")

            instance = DatabaseManager(driverProvider)

            val version = instance.driveMainIndex.getSchemaVersion()

            if (version < DATABASE_VERSION) {
                val logger = Logger.withTag("DatabaseManager")
                logger.i { "Schema version $version < $DATABASE_VERSION — wiping tables" }
                wipeTables(instance.driver)
                OdinDatabase.Schema.create(instance.driver)
                logger.i { "Tables recreated after wipe" }
            }
        }

        private fun wipeTables(driver: SqlDriver) {
            val tables = listOf(
                "AppNotifications",
                "ChatReadCount",
                "ConnectionCache",
                "DriveLocalTagIndex",
                "DriveMainIndex",
                "DriveTagIndex",
                "KeyValue",
                "Outbox"
            )
            tables.forEach { table ->
                driver.execute(null, "DROP TABLE IF EXISTS $table;", 0)
            }
        }
    }

    public val appNotifications: AppNotificationsWrapper by lazy {
        AppNotificationsWrapper(
            driver,
            appNotificationsAdapter,
            this
        )
    }
    public val chatReadCount: ChatReadCountWrapper by lazy {
        ChatReadCountWrapper(driver, chatReadCountAdapter, driveMainIndexAdapter, this)
    }
    public val driveMainIndex: DriveMainIndexWrapper by lazy {
        DriveMainIndexWrapper(
            driver,
            driveMainIndexAdapter,
            this
        )
    }
    public val driveLocalTagIndex: DriveLocalTagIndexWrapper by lazy {
        DriveLocalTagIndexWrapper(
            driver,
            driveLocalTagIndexAdapter,
            this
        )
    }
    public val driveTagIndex: DriveTagIndexWrapper by lazy {
        DriveTagIndexWrapper(
            driver,
            driveTagIndexAdapter,
            this
        )
    }

    // Lazy wrappers
    public val keyValue: KeyValueWrapper by lazy {
        KeyValueWrapper(driver, keyValueAdapter, this)
    }
    public val outbox: OutboxWrapper by lazy {
        OutboxWrapper(driver, outboxAdapter, this)
    }
    public val connectionCache: ConnectionCacheWrapper by lazy {
        ConnectionCacheWrapper(driver, connectionCacheAdapter, this)
    }

    suspend fun <R> executeReadQuery(
        identifier: Int?,
        sql: String,
        mapper: (SqlCursor) -> QueryResult<R>,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)? = null
    ): QueryResult<R> = withContext(dbDispatcher) {
        try {
            driver.executeQuery(identifier, sql, mapper, parameters, binders)
        } catch (e: Exception) {
            logger.e { "executeReadQuery failed: ${e.message}\nSQL: $sql\nStack: ${e.stackTraceToString()}" }
            throw e  // Rethrow if you want the caller to handle, or return a fallback QueryResult
        }
    }

    suspend fun withWriteTransaction(block: (OdinDatabase) -> Unit) {
        withContext(dbDispatcher) {
            database.transaction { block(database) }
        }
    }

    suspend fun withWrite(block: (OdinDatabase) -> Unit) {
        withContext(dbDispatcher) { block(database) }
    }

    suspend fun <R> withWriteValue(block: (OdinDatabase) -> R): R = withContext(dbDispatcher) {
        block(database)
    }

    // VACUUM rewrites the entire DB file, reclaiming space freed by large DELETEs
    // (e.g. the logout wipe). Must run outside a transaction and with exclusive access
    // to the DB, so we run it on dbDispatcher like every other write path.
    suspend fun vacuum() = withContext(dbDispatcher) {
        driver.execute(identifier = null, sql = "VACUUM", parameters = 0)
    }

    override fun close() {
        driver.close()
        logger.i { "Database closed" }
    }
}
