package id.homebase.api.sync.database

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import kotlin.io.path.absolutePathString
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * Coverage for the "data survives logout" bug. Plain-SQLite (no SQLCipher)
 * against a temp-dir on-disk file: WAL mode is enabled, a row is inserted
 * into [DriveMainIndex], then [DatabaseManager.wipeAndRecreate] is called.
 *
 * Two assertions, both of which would catch the reported leak:
 *
 *  1. After the wipe, `SELECT COUNT(*) FROM DriveMainIndex` is zero — both
 *     on the still-open driver AND on a freshly opened driver. A non-zero
 *     count on reopen specifically means a WAL frame replayed the dropped
 *     row, which is the precise failure mode we're guarding against.
 *  2. If a `-wal` sidecar exists on disk after the wipe, it is zero bytes
 *     — proving `wal_checkpoint(TRUNCATE)` ran. We don't require the WAL
 *     to materialise (xerial sqlite-jdbc, in single-connection auto-commit
 *     mode, frequently keeps WAL pages off disk until checkpoint), so this
 *     assertion is a "if it's there, it must be empty" guard rather than
 *     a precondition.
 *
 * Vanilla SQLite is sufficient even though production uses SQLCipher — the
 * wipe path is at the SQL layer (DROP / CREATE / VACUUM / PRAGMA) and the
 * cipher doesn't change how the WAL sidecar behaves.
 */
class WipeAndRecreateSidecarTest {

    private lateinit var tempDir: Path
    private lateinit var dbPath: Path
    private lateinit var walPath: Path
    private lateinit var shmPath: Path

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("wipeAndRecreateTest")
        dbPath = tempDir.resolve("odin-2.db")
        walPath = tempDir.resolve("odin-2.db-wal")
        shmPath = tempDir.resolve("odin-2.db-shm")
    }

    @AfterTest
    fun tearDown() {
        Files.walk(tempDir)
            .sorted(Comparator.reverseOrder())
            .forEach { Files.deleteIfExists(it) }
    }

    @Test
    fun wipeAndRecreate_emptiesDriveMainIndex_andTruncatesWalSidecar() = runTest {
        val driver = openWalDriver(dbPath.absolutePathString())
        val dbm = DatabaseManager({ driver })

        // Insert a row with a deliberately-bad userDate so we can grep for the
        // exact value in the post-wipe assertion. Going via raw SQL rather than
        // through the SQLDelight-generated upsert keeps this test independent
        // of the schema's column count drift over time — wipeAndRecreate
        // protects every row in DriveMainIndex equally.
        val badUserDate = 9_999_999_999_999L
        val identityId = Uuid.random()
        val driveId = Uuid.random()
        val fileId = Uuid.random()
        dbm.withWrite { db ->
            db.driveMainIndexQueries.upsertDriveMainIndex(
                identityId = identityId,
                driveId = driveId,
                fileId = fileId,
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
                userDate = badUserDate,
                created = badUserDate,
                modified = badUserDate,
                fileSystemType = 0L,
                jsonHeader = "{}",
            )
        }

        // Sanity: row visible before wipe.
        assertEquals(1L, countDriveMainIndex(driver), "row should exist pre-wipe")

        // The whole point.
        dbm.wipeAndRecreate()

        // Assertion 1: on the still-open driver, the table is empty.
        assertEquals(
            0L,
            countDriveMainIndex(driver),
            "DriveMainIndex must be empty on the still-open driver after wipeAndRecreate",
        )

        // Assertion 2: if the WAL sidecar exists on disk, it must be empty
        // (proving wal_checkpoint(TRUNCATE) ran). The xerial sqlite-jdbc driver
        // running single-connection often keeps WAL frames off disk entirely,
        // so we tolerate "not present" — but a non-empty WAL would mean the
        // checkpoint failed to run.
        if (walPath.exists()) {
            assertEquals(
                0L,
                walPath.fileSize(),
                "WAL sidecar exists but is non-empty after wipeAndRecreate " +
                    "(size=${walPath.fileSize()} at $walPath) — wipeAndRecreate's " +
                    "checkpoint(TRUNCATE) did not take effect, and a stale page " +
                    "can survive logout.",
            )
        }

        // Belt-and-suspenders: close, reopen against the same file, confirm zero.
        // If a future change re-introduces the leak (WAL frames replay on the
        // new connection), this is the assertion that catches it.
        dbm.close()

        val reopenedDriver = openWalDriver(dbPath.absolutePathString())
        val reopened = DatabaseManager({ reopenedDriver })
        try {
            assertEquals(
                0L,
                countDriveMainIndex(reopenedDriver),
                "DriveMainIndex must be empty after closing and reopening the DB " +
                    "(a non-zero count here means the WAL replayed an old page)",
            )
        } finally {
            reopened.close()
        }
    }

    /**
     * Open a JDBC SQLite driver and force the database into WAL journal mode
     * so the test exercises the same on-disk layout that production
     * (`SupportOpenHelperFactory` on Android, JDBC + WAL on JVM) uses. Without
     * this step the test runs in DELETE journal mode and would never produce
     * the `-wal` sidecar we're trying to verify.
     *
     * Per xerial sqlite-jdbc: a bare `PRAGMA journal_mode=WAL` issued through
     * `driver.execute(..., parameters=0)` is a no-op because that path runs as
     * a *statement* (executeUpdate) rather than a *query* — the driver discards
     * any result rows the pragma emits, but more importantly some pragmas only
     * take effect when consumed as a query. We issue it via `executeQuery` and
     * actually read the returned mode string so we can `require(...)` that WAL
     * is on before the rest of the test runs.
     */
    private fun openWalDriver(path: String): JdbcSqliteDriver {
        val driver = JdbcSqliteDriver("jdbc:sqlite:$path", Properties())
        val resultingMode = driver.executeQuery(
            identifier = null,
            sql = "PRAGMA journal_mode=WAL;",
            mapper = { cursor ->
                cursor.next()
                QueryResult.Value(cursor.getString(0) ?: "")
            },
            parameters = 0,
        ).value
        require(resultingMode.equals("wal", ignoreCase = true)) {
            "Could not switch driver to WAL journal mode (got '$resultingMode'). " +
                "The sidecar test is meaningless in non-WAL modes."
        }
        return driver
    }

    /**
     * Count rows in DriveMainIndex via raw SQL. Avoids depending on the
     * generated SQLDelight query API so the test stays focused on the wipe
     * behaviour even if column count drifts.
     */
    private fun countDriveMainIndex(driver: JdbcSqliteDriver): Long {
        return driver.executeQuery(
            identifier = null,
            sql = "SELECT COUNT(*) FROM DriveMainIndex",
            mapper = { cursor ->
                cursor.next()
                QueryResult.Value(cursor.getLong(0) ?: 0L)
            },
            parameters = 0,
        ).value
    }
}
