package id.homebase.api.sync.database

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.createFile
import kotlin.io.path.exists
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for [deleteDatabaseFiles] — the suffix loop / file-system call
 * that [DatabaseManager.initializeWithRecovery] uses to wipe a corrupted
 * database. Targets the JVM actual but the body under test lives in
 * commonMain, so iOS/Android take the same code path by symmetry.
 *
 * Tests against a temp directory rather than the factory's real path so
 * they don't clobber a developer's local DB when running the suite.
 */
class DeleteDatabaseFilesTest {

    private lateinit var tempDir: Path
    private lateinit var dbPath: String

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("deleteDbTest")
        dbPath = tempDir.resolve("odin-2.db").absolutePathString()
    }

    @AfterTest
    fun tearDown() {
        // Defensive: even if a test failed mid-way, clean up its temp dir.
        Files.walk(tempDir)
            .sorted(Comparator.reverseOrder())
            .forEach { Files.deleteIfExists(it) }
    }

    @Test
    fun deletes_primary_and_all_three_siblings_when_all_present() {
        Path.of("$dbPath").createFile()
        Path.of("$dbPath-journal").createFile()
        Path.of("$dbPath-wal").createFile()
        Path.of("$dbPath-shm").createFile()

        deleteDatabaseFiles(dbPath)

        assertFalse(Path.of("$dbPath").exists(), "primary should be deleted")
        assertFalse(Path.of("$dbPath-journal").exists(), "-journal should be deleted")
        assertFalse(Path.of("$dbPath-wal").exists(), "-wal should be deleted")
        assertFalse(Path.of("$dbPath-shm").exists(), "-shm should be deleted")
    }

    @Test
    fun is_silent_no_op_when_no_files_exist() {
        // No files created. Function must not throw — the `mustExist = false`
        // contract on SystemFileSystem.delete is the load-bearing detail here.
        deleteDatabaseFiles(dbPath)

        assertFalse(Path.of(dbPath).exists())
    }

    @Test
    fun deletes_primary_silently_skipping_missing_siblings() {
        // Only the primary file exists; the three sibling suffixes are
        // missing. Function must delete the primary and silently no-op the
        // siblings (the common shape after a clean shutdown that left only
        // the .db file and no WAL).
        Path.of(dbPath).createFile()

        deleteDatabaseFiles(dbPath)

        assertFalse(Path.of(dbPath).exists(), "primary should be deleted")
        // Sanity-check that we didn't accidentally create the siblings either.
        assertFalse(Path.of("$dbPath-journal").exists())
        assertFalse(Path.of("$dbPath-wal").exists())
        assertFalse(Path.of("$dbPath-shm").exists())
    }

    @Test
    fun deletes_only_files_matching_our_suffix_list() {
        // Defense against future drift: if someone ever adds a new SQLite
        // side file (e.g. `-jr`) we want a test failure, not silent
        // partial cleanup. Today's contract: only the four known suffixes
        // are touched.
        Path.of(dbPath).createFile()
        Path.of("$dbPath-journal").createFile()
        val unrelated = tempDir.resolve("unrelated.txt")
        unrelated.createFile()

        deleteDatabaseFiles(dbPath)

        assertFalse(Path.of(dbPath).exists())
        assertFalse(Path.of("$dbPath-journal").exists())
        assertTrue(unrelated.exists(), "unrelated files in the directory must survive")
    }
}
