package id.homebase.api.sync.database

import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Drift guard for the wipe-on-version-bump migration model (no .sqm migrations here).
 *
 * `OdinDatabase.Schema.create()` runs `CREATE TABLE IF NOT EXISTS`, so on an EXISTING
 * install a table whose .sq shape changed keeps its old on-disk shape — while the
 * generated queries reference the new columns. The first such query throws
 * "no such column" (fatal in any scope without a handler). This shipped twice:
 * `DriveMainIndex.fileState` (fixed by the v5 bump) and `LocationPoint.steps/bat`
 * (added 2026-06-14 with NO bump — crashed production testers opening the Location
 * tab, fixed by the v6 bump).
 *
 * The rule this test enforces: ANY change to a CREATE TABLE shape must ride a
 * version bump. On failure, do all three together:
 *  1. bump [DatabaseManager.DATABASE_VERSION],
 *  2. bump the `-- Version: N` stamp on DriveMainIndex.sq's CREATE TABLE,
 *  3. update [EXPECTED_TABLE_SHAPE_SHA256] below.
 * (CREATE INDEX changes are exempt: an index over existing columns applies cleanly
 * to an old-shape table, and an index over a NEW column implies a table-shape
 * change this hash already catches.)
 */
class SchemaVersionGuardTest {

    @Test
    fun versionStampInDriveMainIndexSq_matchesDatabaseVersionConstant() {
        val stamped = Regex("-- Version: (\\d+)")
            .find(driveMainIndexSq().readText())
            ?.groupValues?.get(1)?.toLong()
        assertEquals(
            DatabaseManager.DATABASE_VERSION, stamped,
            "DriveMainIndex.sq's '-- Version: N' stamp must equal DatabaseManager.DATABASE_VERSION — " +
                "the on-disk stamp is what the stale-schema rebuild compares against",
        )
    }

    @Test
    fun createTableShapes_requireVersionBumpWhenChanged() {
        val statements = sqldelightDir().walkTopDown()
            .filter { it.isFile && it.extension == "sq" }
            .sortedBy { it.name }
            .flatMap { file ->
                val noComments = file.readLines().joinToString("\n") { it.substringBefore("--") }
                Regex("CREATE TABLE[^;]*;", RegexOption.DOT_MATCHES_ALL)
                    .findAll(noComments)
                    .map { it.value.split(Regex("\\s+")).joinToString(" ").trim() }
            }
            .toList()

        assertEquals(
            DatabaseManager.TABLE_NAMES.size, statements.size,
            "CREATE TABLE count no longer matches DatabaseManager.TABLE_NAMES — " +
                "a new table must be added there too (wipeAndRecreate/logout skips it otherwise)",
        )

        val digest = MessageDigest.getInstance("SHA-256")
            .digest(statements.joinToString("\n").toByteArray())
            .joinToString("") { "%02x".format(it) }
        assertEquals(
            EXPECTED_TABLE_SHAPE_SHA256, digest,
            "A CREATE TABLE shape changed. Existing installs keep the OLD table shape " +
                "(Schema.create is CREATE IF NOT EXISTS) and will crash with 'no such column' " +
                "unless the schema version is bumped. Bump DatabaseManager.DATABASE_VERSION AND " +
                "the '-- Version: N' stamp in DriveMainIndex.sq, then update this hash.",
        )
    }

    private fun sqldelightDir(): File {
        // Gradle runs jvmTest with the module directory as the working dir, but be
        // tolerant of a repo-root working dir too.
        val candidates = listOf(
            File("src/commonMain/sqldelight"),
            File("homebase-api/src/commonMain/sqldelight"),
        )
        val dir = candidates.firstOrNull { it.isDirectory }
        assertTrue(dir != null, "could not locate the sqldelight source dir from ${File(".").absolutePath}")
        return dir
    }

    private fun driveMainIndexSq(): File =
        sqldelightDir().walkTopDown().first { it.name == "DriveMainIndex.sq" }

    private companion object {
        // SHA-256 over all CREATE TABLE statements (comments stripped, whitespace
        // collapsed, files ordered by name). See the class kdoc before changing.
        const val EXPECTED_TABLE_SHAPE_SHA256 =
            "e1e996613607e0cc8584294f852d45a9d75b4f42772b22531648dce9497e1bb2"
    }
}
