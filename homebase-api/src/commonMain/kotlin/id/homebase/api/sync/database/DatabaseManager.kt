package id.homebase.api.sync.database

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import co.touchlab.kermit.Logger
import id.homebase.api.coroutines.ioDispatcher
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.TimeSource

/**
 * One-shot signal that the local DB was just wiped because the on-disk schema
 * version was older than [DatabaseManager.DATABASE_VERSION]. The UI consumes this
 * (see `AppNavHost`) to show a snackbar so the user understands why their data
 * appears to have vanished while DriveSync repopulates it from the server.
 *
 * Only emitted from [DatabaseManager.initialize] — the logout-driven wipe in
 * `DriveSyncManager.clearStorage` calls `wipeAndRecreate` directly and is NOT
 * an "upgrade".
 *
 * Sticky: stays in [JustUpgraded] until [DatabaseManager.markUpgradeConsumed]
 * is called, so a consumer that subscribes after the synchronous wipe finished
 * still sees the signal.
 */
sealed interface DatabaseUpgradeState {
    data object Idle : DatabaseUpgradeState
    data class JustUpgraded(val fromVersion: Int) : DatabaseUpgradeState
}

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
private val locationPointAdapter = LocationPoint.Adapter(
    flushedFileUidAdapter = UuidAdapter
)
private val outboxAdapter = Outbox.Adapter(
    driveIdAdapter = UuidAdapter,
    uniqueIdAdapter = UuidAdapter,
    dependencyUniqueIdAdapter = UuidAdapter
)
private val connectionCacheAdapter = ConnectionCache.Adapter(
    identityIdAdapter = UuidAdapter
)

/**
 * Latency breakdown of the most recent read.
 *
 * - [queueWaitMs] — time the request sat waiting for a read-dispatcher slot. A large
 *   value means scheduling pressure, not a slow query.
 * - [sqlMs] — total wall-clock spent inside `driver.executeQuery` (raw-SQL path) or
 *   the SQLDelight `executeAs*` block (typed-query path). This is "SQL + mapper",
 *   not "SQL alone".
 * - [mapperMs] — for the raw-SQL path, the subset of [sqlMs] spent on mapper CPU
 *   *between* `SqlCursor.next()` calls (field gets, object construction, JSON
 *   deserialization). The "real SQL" portion is therefore `sqlMs - mapperMs`.
 *   Zero for the typed-query path ([readValue]) because we don't instrument the
 *   SQLDelight-generated mapper there (its mapper is a thin code-gen constructor,
 *   not a JSON deserialization, so the split would be noise).
 *
 * Surfaced via [DatabaseManager.lastReadTiming] — but note this is a SINGLE shared cell
 * overwritten by every read, so it is only reliable when you control concurrency (e.g. the
 * read-lane unit test). Do NOT read it after the fact to attribute timing to a specific
 * query from arbitrary code — a concurrent read on another coroutine will have clobbered it
 * (this caused garbled `queueWait` in `SlowMessageFetch`). For accurate per-query timing,
 * use the `SlowDbRead` log line that [executeReadQuery]/[readValue] emit for that exact read.
 */
data class ReadTiming(
    val queueWaitMs: Long,
    val sqlMs: Long,
    val mapperMs: Long = 0,
)

class DatabaseManager(
    driverProvider: () -> SqlDriver,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
    // Reads run off the single write [dispatcher] so they don't queue behind a long
    // write transaction. True concurrency comes from each platform driver's reader
    // pool under WAL (iOS NativeSqliteDriver.maxReaderConnections, Android's framework
    // WAL pool, desktop's per-thread JDBC connections); where a platform can't serve a
    // concurrent reader the reads simply serialize — benign, never incorrect. We still
    // run on the *existing* single driver — no second managed SqlDriver/connection.
    private val readDispatcher: CoroutineDispatcher =
        ioDispatcher.limitedParallelism(READ_PARALLELISM)
) : AutoCloseable {
    private val logger = Logger.withTag("DatabaseManager")
    private var database: OdinDatabase
    internal var driver: SqlDriver = driverProvider()

    // Latency split of the most recent read; see [ReadTiming]. Updated on every
    // executeReadQuery; readers should treat it as best-effort (concurrent reads
    // race on this single cell) — it's a diagnostic, not a correctness signal.
    private val _lastReadTiming = atomic<ReadTiming?>(null)
    val lastReadTiming: ReadTiming? get() = _lastReadTiming.value

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
            locationPointAdapter,
            outboxAdapter
        )
        logger.i { "Database initialized" }

        // One-time audit of the effective SQLite mode, so each platform's actual
        // journal mode / busy timeout is visible in homebase.log. WAL + a non-zero
        // busy_timeout are what keep concurrent reads/writes from throwing
        // SQLITE_BUSY (which knocked the desktop WebSocket offline). synchronous is
        // 0=OFF 1=NORMAL 2=FULL 3=EXTRA.
        val journalMode = readPragmaString("PRAGMA journal_mode") ?: "?"
        val busyTimeoutMs = readPragmaLong("PRAGMA busy_timeout") ?: -1L
        val synchronous = readPragmaLong("PRAGMA synchronous") ?: -1L
        logger.i {
            "DB pragmas: journal_mode=$journalMode busy_timeout=${busyTimeoutMs}ms " +
                "synchronous=$synchronous readParallelism=$READ_PARALLELISM"
        }
    }

    companion object {
        private const val DATABASE_VERSION =
            5  // Increase to wipe the database and rebuild all tables

        // Max concurrent reads on [readDispatcher]. Kept in step with iOS
        // NativeSqliteDriver.maxReaderConnections=4 so the dispatcher doesn't admit more
        // readers than the platform connection pool can serve. On Android the SQLCipher WAL
        // pool size is platform-determined (net.zetetic's SupportOpenHelperFactory exposes no
        // knob, unlike iOS) — if it serves fewer than this, the extra reads block in
        // SQLiteConnectionPool.waitForConnection, which is counted inside the read's `sql=`
        // time (the wait happens inside driver.executeQuery), so it surfaces as an inflated
        // `sql=` in a SlowDbRead line. That's the verification signal: if device logs show a
        // normally-fast read with a large `sql=` during a read burst, lower this to match the
        // pool. Since reads are off the Main thread (DriveMainIndexWrapper + the list readers
        // both route through the read lane), such a wait queues a background read, not the UI.
        private const val READ_PARALLELISM = 4

        // A read slower than this logs a SlowDbRead warn with its queueWait/sql split.
        private val SLOW_READ_THRESHOLD = 50.milliseconds

        private lateinit var instance: DatabaseManager
        val appDb: DatabaseManager get() = instance

        // Companion-scoped so consumers (AppNavHost) can observe an upgrade even when
        // they subscribe *after* initialize() finished. The state is sticky until
        // markUpgradeConsumed() is called explicitly.
        private val _databaseUpgradeState =
            MutableStateFlow<DatabaseUpgradeState>(DatabaseUpgradeState.Idle)
        val databaseUpgradeState: StateFlow<DatabaseUpgradeState> =
            _databaseUpgradeState.asStateFlow()

        /**
         * Called by the UI once it has shown the upgrade snackbar so the sticky
         * [DatabaseUpgradeState.JustUpgraded] is cleared. Without this, recomposition
         * would keep re-firing the snackbar effect.
         */
        fun markUpgradeConsumed() {
            _databaseUpgradeState.value = DatabaseUpgradeState.Idle
        }

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
            "LocationPoint",
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
                _databaseUpgradeState.value =
                    DatabaseUpgradeState.JustUpgraded(fromVersion = version.toInt())
            }
        }

        /**
         * Open the database via [factory], using the key from
         * [DatabaseKeyManager.getOrGenerateKey]. If the open throws (corrupted
         * file, undecryptable with the stored key, schema mismatch), delete the
         * on-disk files via [DatabaseDriverFactory.deleteOnDiskFiles], rotate
         * the encryption key, and retry once. Replaces the recovery dance each
         * platform entry point used to implement inline.
         *
         * The catch is `Exception` rather than `Throwable` to preserve parity
         * with the prior per-platform implementations — `Error` (OOM,
         * `StackOverflow`) is not swallowed.
         */
        suspend fun initializeWithRecovery(factory: DatabaseDriverFactory) {
            val key = DatabaseKeyManager.getOrGenerateKey()
            try {
                initialize { factory.createDriver(key) }
            } catch (e: Exception) {
                Logger.withTag("DatabaseManager")
                    .e(e) { "initializeWithRecovery: open failed, resetting" }
                factory.deleteOnDiskFiles()
                DatabaseKeyManager.clearKey()
                val freshKey = DatabaseKeyManager.getOrGenerateKey()
                initialize { factory.createDriver(freshKey) }
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
    val locationPoint: LocationPointWrapper by lazy {
        LocationPointWrapper(driver, locationPointAdapter, this)
    }
    val connectionCache: ConnectionCacheWrapper by lazy {
        ConnectionCacheWrapper(driver, connectionCacheAdapter, this)
    }

    // Reads run on [readDispatcher], NOT the single write [dispatcher], so a read
    // issued while a long write transaction holds the writer slot starts
    // immediately instead of queueing behind it. [requestedAt]..inside-withContext
    // measures that queue-wait; the inner mark measures the SQL itself. The split is
    // stored in [lastReadTiming] and warn-logged when slow, so device logs show
    // whether read latency was scheduling or SQL.
    //
    // SlowDbRead reports a triple: `queueWait` / `sql` / `mapper`. The
    // driver invokes the user [mapper] *inline* (cursor.open → mapper(cursor)
    // → cursor.close all under `driver.executeQuery`), and for shapes like
    // QueryBatch that mapper does `OdinSystemSerializer.deserialize<HomebaseFile>(jsonHeader)`
    // per row. Without splitting, big-payload deserialization shows up under
    // `sql=` and looks like a slow query plan. We instrument the cursor's
    // `next()` (each call is a row pull from SQLite) and treat that as
    // "real SQL"; whatever wall-clock the mapper spends between `next()`
    // calls is reported as `mapper=`. The two should sum to the legacy
    // `sql=` number ± a few µs of accounting noise.
    suspend fun <R> executeReadQuery(
        identifier: Int?,
        sql: String,
        mapper: (SqlCursor) -> QueryResult<R>,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)? = null
    ): QueryResult<R> {
        val requestedAt = TimeSource.Monotonic.markNow()
        return withContext(readDispatcher) {
            val queueWait = requestedAt.elapsedNow()
            val sqlStart = TimeSource.Monotonic.markNow()
            try {
                // Capture the wrapper used for the (single) mapper invocation so
                // we can read its accumulated next()-time after the driver call
                // returns. A `var` is fine here — `driver.executeQuery` is
                // synchronous within this coroutine continuation.
                var timing: TimingCursor? = null
                val result = driver.executeQuery(
                    identifier,
                    sql,
                    { rawCursor ->
                        val tc = TimingCursor(rawCursor)
                        timing = tc
                        mapper(tc)
                    },
                    parameters,
                    binders,
                )
                val total = sqlStart.elapsedNow()
                // If for some reason the driver never invoked the mapper (it
                // shouldn't, but be defensive), credit everything to SQL.
                val sqlOnly = timing?.nextNanos?.nanoseconds ?: total
                // Monotonic clocks guarantee mapper ≥ 0, but coerce as a belt-
                // and-braces against any future driver oddity (cached cursors,
                // pre-fetched rows where next() reports near-zero).
                val mapperOnly = (total - sqlOnly).coerceAtLeast(Duration.ZERO)
                _lastReadTiming.value = ReadTiming(
                    queueWaitMs = queueWait.inWholeMilliseconds,
                    sqlMs = total.inWholeMilliseconds,
                    mapperMs = mapperOnly.inWholeMilliseconds,
                )
                if (queueWait + total > SLOW_READ_THRESHOLD) {
                    logger.w {
                        "SlowDbRead queueWait=$queueWait sql=$sqlOnly mapper=$mapperOnly sqlPreview=${sql.take(120)}"
                    }
                }
                result
            } catch (e: Exception) {
                logger.e { "executeReadQuery failed: ${e.message}\nSQL: $sql\nStack: ${e.stackTraceToString()}" }
                throw e  // Rethrow if you want the caller to handle, or return a fallback QueryResult
            }
        }
    }

    /**
     * Accumulates the wall-clock time spent in [SqlCursor.next] (the row-
     * advance call) for the wrapping read. This is a close proxy for SQL
     * time in practice — but it isn't literally "all driver work":
     * column extraction via `getString` / `getLong` etc. is also driver
     * work, just typically trivial against an already-fetched cursor row
     * buffer. Whatever wall-clock the mapper spends *between* `next()`
     * calls (column gets + object construction + JSON deserialization)
     * is what the surrounding `total - nextNanos` in [executeReadQuery]
     * reports as `mapper=`. The split is honest for the case that
     * motivated it (QueryBatch's per-row `HomebaseFile` deserialization);
     * a hypothetical mapper that pushed heavy work into `getString`
     * callbacks (e.g. lazy column decryption) would land that work in
     * `mapper=` even though it's driver-side — read the numbers with
     * that nuance in mind.
     *
     * Uses Kotlin's interface delegation so `getString`/`getLong`/etc. forward
     * to the underlying cursor unchanged — only `next()` is instrumented.
     */
    private class TimingCursor(private val delegate: SqlCursor) : SqlCursor by delegate {
        var nextNanos: Long = 0L
            private set

        override fun next(): QueryResult<Boolean> {
            val s = TimeSource.Monotonic.markNow()
            try {
                return delegate.next()
            } finally {
                nextNanos += s.elapsedNow().inWholeNanoseconds
            }
        }
    }

    /**
     * Run a read that uses the SQLDelight generated-query DSL (rather than raw SQL) on the
     * read lane — the counterpart to [executeReadQuery] for the `*Wrapper` reads that call
     * `delegate.x().executeAsList()`. Same [readDispatcher] + queue-wait/SQL split +
     * SlowDbRead warn; [label] names the read in the log since there's no SQL string.
     *
     * **No `sql=` / `mapper=` split here** (unlike [executeReadQuery]). The mapper that
     * runs inside `executeAsList` is owned by SQLDelight code-gen — typically a thin
     * `(col1, col2, …) -> Row(col1, col2, …)` — so the legacy single-timer reading is
     * accurate enough for the typed-query path. The split exists for [executeReadQuery]
     * because QueryBatch's mapper does row-level JSON deserialization that can dwarf the
     * SQL itself; SQLDelight rows don't have that hazard.
     *
     * Keep [block] to the DB read itself (executeAsList / executeAsOneOrNull) and do any
     * mapping/deserialization outside, so a read-lane slot is held only for the SQL — not
     * for CPU-bound row mapping.
     */
    suspend fun <R> readValue(label: String, block: () -> R): R {
        val requestedAt = TimeSource.Monotonic.markNow()
        return withContext(readDispatcher) {
            val queueWait = requestedAt.elapsedNow()
            val sqlStart = TimeSource.Monotonic.markNow()
            try {
                val result = block()
                val sqlElapsed = sqlStart.elapsedNow()
                _lastReadTiming.value =
                    ReadTiming(queueWait.inWholeMilliseconds, sqlElapsed.inWholeMilliseconds)
                if (queueWait + sqlElapsed > SLOW_READ_THRESHOLD) {
                    logger.w { "SlowDbRead queueWait=$queueWait sql=$sqlElapsed read=$label" }
                }
                result
            } catch (e: Exception) {
                logger.e { "read failed [$label]: ${e.message}\nStack: ${e.stackTraceToString()}" }
                throw e
            }
        }
    }

    suspend fun withWriteTransaction(block: (OdinDatabase) -> Unit) {
        withContext(dispatcher) {
            database.transaction { block(database) }
        }
    }

    suspend fun withWrite(block: (OdinDatabase) -> Unit) {
        withContext(dispatcher) { block(database) }
    }

    suspend fun <R> withWriteValue(block: (OdinDatabase) -> R): R = withContext(dispatcher) {
        block(database)
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

        // Pre-wipe snapshot of the SQLite-internal page state. Captured *before* the
        // DROPs so we have a baseline to compare against after the VACUUM + checkpoint.
        // See [readPragmaLong] / [readPragmaCheckpoint] for what each pragma reports.
        val journalMode = readPragmaString("PRAGMA journal_mode") ?: "?"
        val pagesBefore = readPragmaLong("PRAGMA page_count") ?: -1L
        val freelistBefore = readPragmaLong("PRAGMA freelist_count") ?: -1L
        log.i {
            "wipeAndRecreate: pre-wipe journal_mode=$journalMode page_count=$pagesBefore freelist_count=$freelistBefore"
        }

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

        // Force the WAL file to truncate to zero, so an on-disk `-wal` left over
        // from before the wipe cannot carry pages forward into the next session.
        // No-op when journal_mode != WAL (returns busy=0,log=0,ckpt=0 cleanly).
        // The motivating bug: a user reported chat rows with corrupt timestamps
        // surviving logout. wipeAndRecreate's tables were dropped, but if WAL
        // mode was active and the WAL sidecar wasn't truncated, the next open
        // could still see those pages. This is the cheapest probe that proves
        // it didn't.
        val checkpoint = readPragmaCheckpoint("PRAGMA wal_checkpoint(TRUNCATE)")
        val pagesAfter = readPragmaLong("PRAGMA page_count") ?: -1L
        val freelistAfter = readPragmaLong("PRAGMA freelist_count") ?: -1L
        if (checkpoint != null) {
            val (busy, logPages, ckptPages) = checkpoint
            // busy=1 means another connection held the WAL — should never happen here
            // because we're on the single-writer dbDispatcher with no other clients.
            // logPages > 0 after wipe + VACUUM is the red flag: it means we VACUUMed
            // into a fresh main DB but the WAL still holds pages that a future open
            // would replay on top.
            if (busy != 0L || logPages > 0L) {
                log.e {
                    "wipeAndRecreate: WAL checkpoint reports busy=$busy log_pages=$logPages " +
                        "ckpt_pages=$ckptPages — WAL was non-empty after wipe, potential leak"
                }
            } else {
                log.i {
                    "wipeAndRecreate: WAL checkpoint clean (busy=0 log=0 ckpt=$ckptPages)"
                }
            }
        }
        log.i {
            "wipeAndRecreate: post-wipe page_count=$pagesAfter freelist_count=$freelistAfter " +
                "(was page_count=$pagesBefore freelist_count=$freelistBefore)"
        }

        log.i { "wipeAndRecreate: completed (${TABLE_NAMES.size} tables)" }
    }

    /**
     * Read a single string from a PRAGMA that returns one row, one column
     * (e.g. `PRAGMA journal_mode`). Returns `null` if the pragma fails or the
     * cursor is empty — we never want a probe call to abort wipeAndRecreate.
     */
    private fun readPragmaString(sql: String): String? = runCatching {
        driver.executeQuery(
            identifier = null,
            sql = sql,
            mapper = { cursor ->
                val value = if (cursor.next().value) cursor.getString(0) else null
                QueryResult.Value(value)
            },
            parameters = 0,
        ).value
    }.getOrNull()

    /**
     * Read a single integer from a PRAGMA that returns one row, one column
     * (e.g. `PRAGMA page_count`, `PRAGMA freelist_count`). Returns `null` on
     * failure — probes must never throw.
     */
    private fun readPragmaLong(sql: String): Long? = runCatching {
        driver.executeQuery(
            identifier = null,
            sql = sql,
            mapper = { cursor ->
                val value = if (cursor.next().value) cursor.getLong(0) else null
                QueryResult.Value(value)
            },
            parameters = 0,
        ).value
    }.getOrNull()

    /**
     * Read the result of `PRAGMA wal_checkpoint(...)`, which returns a single
     * row of three integers: (busy, log_frames, checkpoint_frames). Returns
     * `null` if the pragma is unsupported (e.g. wasmJs's sql.js doesn't
     * implement the WAL checkpoint pragma) or the call throws.
     */
    private fun readPragmaCheckpoint(sql: String): Triple<Long, Long, Long>? = runCatching {
        driver.executeQuery(
            identifier = null,
            sql = sql,
            mapper = { cursor ->
                val value = if (cursor.next().value) {
                    Triple(
                        cursor.getLong(0) ?: 0L,
                        cursor.getLong(1) ?: 0L,
                        cursor.getLong(2) ?: 0L,
                    )
                } else null
                QueryResult.Value(value)
            },
            parameters = 0,
        ).value
    }.getOrNull()

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
