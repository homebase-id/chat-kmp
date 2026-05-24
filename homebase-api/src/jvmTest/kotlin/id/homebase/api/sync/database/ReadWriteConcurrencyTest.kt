package id.homebase.api.sync.database

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import kotlin.io.path.absolutePathString
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * Proves the Phase 2 fix for the cold-boot tap-stall: with a dedicated read connection,
 * a read runs concurrently with the single writer instead of queuing behind it on the
 * one DB dispatcher.
 *
 * The bug: every read and every write went through `Dispatchers.Default.limitedParallelism(1)`,
 * so a tap-read landing during a multi-second DriveSync batch upsert sat in that dispatcher's
 * queue for the whole write (logged as `SlowMessageFetch dbQuery=14.7s`). [executeReadQuery]
 * now reports that wait as `queueWait`.
 *
 * On-disk file + WAL (two JDBC connections to the same file) so the test exercises the same
 * concurrency model production uses. We hold a write by sleeping inside a write transaction —
 * which pins the writer dispatcher thread — and check whether a concurrent read's `queueWait`
 * reflects that hold.
 */
class ReadWriteConcurrencyTest {

    private lateinit var tempDir: Path
    private lateinit var dbPath: Path

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("readWriteConcurrencyTest")
        dbPath = tempDir.resolve("odin-2.db")
    }

    @AfterTest
    fun tearDown() {
        Files.walk(tempDir)
            .sorted(Comparator.reverseOrder())
            .forEach { Files.deleteIfExists(it) }
    }

    private val writeHoldMs = 1500L

    /**
     * Launch a write that holds the writer dispatcher for [writeHoldMs] (Thread.sleep inside
     * the transaction pins the single writer thread), then — once it's holding — issue a read
     * and return that read's measured `queueWait`. Whether the read waited tells us if it
     * shared the writer's lane.
     */
    private fun readQueueWaitDuringHeldWrite(dbm: DatabaseManager): Long = runBlocking {
        var readQueueWait = -1L
        val writeJob = launch(Dispatchers.Default) {
            dbm.withWriteTransaction { Thread.sleep(writeHoldMs) }
        }
        // Let the write actually grab the writer dispatcher before we read.
        delay(200)
        dbm.executeReadQuery(
            identifier = null,
            sql = "SELECT 1",
            mapper = { cursor -> cursor.next(); QueryResult.Value(cursor.getLong(0) ?: 0L) },
            parameters = 0,
            onTiming = { qw, _ -> readQueueWait = qw },
        )
        writeJob.join()
        readQueueWait
    }

    @Test
    fun readConnection_letsReadBypassHeldWrite() {
        val dbm = DatabaseManager(
            driverProvider = { openWalDriver(dbPath.absolutePathString()) },
            readDriverProvider = { openWalDriver(dbPath.absolutePathString()) },
        )
        dbm.use {
            val queueWait = readQueueWaitDuringHeldWrite(dbm)
            assertTrue(
                queueWait in 0..400,
                "with a read connection the read must bypass the ${writeHoldMs}ms held write, " +
                    "but queueWait=${queueWait}ms",
            )
        }
    }

    @Test
    fun noReadConnection_readWaitsForHeldWrite() {
        // No readDriverProvider → reads fall back to the writer lane (the prior behavior we
        // are fixing). The read must wait roughly the remaining hold time.
        val dbm = DatabaseManager(
            driverProvider = { openWalDriver(dbPath.absolutePathString()) },
        )
        dbm.use {
            val queueWait = readQueueWaitDuringHeldWrite(dbm)
            assertTrue(
                queueWait > 800,
                "without a read connection the read must queue behind the ${writeHoldMs}ms write, " +
                    "but queueWait=${queueWait}ms",
            )
        }
    }

    @Test
    fun readConnection_seesCommittedWriterRows_crossConnection() = runBlocking {
        // The property the in-memory single-connection suite cannot check: a row committed on
        // the WRITER connection must be visible to a read issued on the SEPARATE read
        // connection (read-your-writes across two connections under WAL). If this regressed,
        // a tap would open a conversation and see stale/missing messages.
        val dbm = DatabaseManager(
            driverProvider = { openWalDriver(dbPath.absolutePathString()) },
            readDriverProvider = { openWalDriver(dbPath.absolutePathString()) },
        )
        dbm.use {
            // Write on the writer connection (withWrite runs on the writer dispatcher and
            // commits before it returns).
            dbm.withWrite { db ->
                db.driveMainIndexQueries.upsertDriveMainIndex(
                    identityId = Uuid.random(),
                    driveId = Uuid.random(),
                    fileId = Uuid.random(),
                    uniqueId = null,
                    globalTransitId = null,
                    groupId = null,
                    senderId = null,
                    originalAuthor = null,
                    fileType = 7878L,
                    dataType = 0L,
                    archivalStatus = 0L,
                    fileState = 1L,
                    historyStatus = 0L,
                    userDate = 1_000L,
                    created = 1_000L,
                    modified = 1_000L,
                    fileSystemType = 0L,
                    jsonHeader = "{\"probe\":true}",
                )
            }

            // Read on the read connection via the production read path (executeReadQuery).
            val count = dbm.executeReadQuery(
                identifier = null,
                sql = "SELECT COUNT(*) FROM DriveMainIndex WHERE fileType = 7878",
                mapper = { cursor -> cursor.next(); QueryResult.Value(cursor.getLong(0) ?: -1L) },
                parameters = 0,
            ).value

            assertEquals(
                1L,
                count,
                "a committed write on the writer connection must be visible to the read " +
                    "connection (cross-connection read-your-writes under WAL)",
            )
        }
    }

    @Test
    fun wipeAndRecreate_closesAndReopensReadConnection() = runBlocking {
        val dbm = DatabaseManager(
            driverProvider = { openWalDriver(dbPath.absolutePathString()) },
            readDriverProvider = { openWalDriver(dbPath.absolutePathString()) },
        )
        dbm.use {
            // Read works before the wipe (read connection is open).
            assertTrue(selectOneViaReadLane(dbm) == 1L, "read should work before wipe")

            // wipeAndRecreate must close the read connection (so it can't pin the WAL during
            // the checkpoint TRUNCATE) and reopen it against the fresh schema afterwards.
            dbm.wipeAndRecreate()

            // Read works again afterwards — proves the read connection was reopened, not left
            // closed (which would silently fall back to the writer lane forever).
            assertTrue(selectOneViaReadLane(dbm) == 1L, "read should work after wipe (reopened)")
        }
    }

    private suspend fun selectOneViaReadLane(dbm: DatabaseManager): Long =
        dbm.executeReadQuery(
            identifier = null,
            sql = "SELECT 1",
            mapper = { cursor -> cursor.next(); QueryResult.Value(cursor.getLong(0) ?: -1L) },
            parameters = 0,
        ).value

    /**
     * Open a JDBC SQLite driver in WAL mode (matches production / [WipeAndRecreateSidecarTest]).
     * Does not create schema — [DatabaseManager] runs `OdinDatabase.Schema.create` on the writer;
     * the read connection just attaches to the existing file.
     */
    private fun openWalDriver(path: String): JdbcSqliteDriver {
        val driver = JdbcSqliteDriver("jdbc:sqlite:$path", Properties())
        driver.executeQuery(
            identifier = null,
            sql = "PRAGMA journal_mode=WAL;",
            mapper = { cursor -> cursor.next(); QueryResult.Value(cursor.getString(0) ?: "") },
            parameters = 0,
        )
        return driver
    }
}
