package id.homebase.api.file

import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Cross-platform red-green test for [safeDeleteRecursively]. Uses an okio
 * [FakeFileSystem], so the shared safety logic is exercised on every target.
 */
class SafeDeleteTest {

    // A realistic, comfortably-long trusted base directory.
    private val base = "/data/data/id.homebase.test/cache"

    private fun FakeFileSystem.seedDir(path: String, vararg files: String) {
        createDirectories(path.toPath())
        for (f in files) {
            val fp = path.toPath() / f
            fp.parent?.let { createDirectories(it) }
            write(fp) { write(ByteArray(8)) }
        }
    }

    @Test
    fun deletesDirectoryStrictlyUnderBase() {
        val fs = FakeFileSystem()
        fs.seedDir(base)
        fs.seedDir("$base/homebase-payloads", "a.bin", "nested/b.bin")

        val deleted = safeDeleteRecursively(base, "homebase-payloads", fs)

        assertTrue(deleted, "must delete a real directory under the base")
        assertFalse(fs.exists("$base/homebase-payloads".toPath()), "target dir must be gone")
        assertTrue(fs.exists(base.toPath()), "the base dir itself must survive")
    }

    @Test
    fun deletesNestedRelativeSubPath() {
        val fs = FakeFileSystem()
        fs.seedDir(base)
        fs.seedDir("$base/a/b/c", "x.bin")

        val deleted = safeDeleteRecursively(base, "a/b/c", fs)

        assertTrue(deleted)
        assertFalse(fs.exists("$base/a/b/c".toPath()))
        assertTrue(fs.exists("$base/a/b".toPath()), "only the named sub-path is removed")
    }

    @Test
    fun missingTarget_returnsFalse_doesNotThrow() {
        val fs = FakeFileSystem()
        fs.seedDir(base)
        assertFalse(safeDeleteRecursively(base, "does-not-exist", fs))
    }

    // --- belt & suspenders: every one of these must delete NOTHING ---

    @Test
    fun refusesBlankBase() {
        val fs = FakeFileSystem()
        assertFalse(safeDeleteRecursively("", "cache", fs))
        assertFalse(safeDeleteRecursively("   ", "cache", fs))
    }

    @Test
    fun refusesTooShortOrRootBase() {
        val fs = FakeFileSystem()
        assertFalse(safeDeleteRecursively("/", "cache", fs))
    }

    @Test
    fun refusesBlankRelativeSubPath() {
        val fs = FakeFileSystem()
        fs.seedDir(base, "keep.bin")
        assertFalse(safeDeleteRecursively(base, "", fs))
        assertFalse(safeDeleteRecursively(base, "   ", fs))
        assertTrue(fs.exists("$base/keep.bin".toPath()), "a blank relative path must delete nothing")
    }

    @Test
    fun refusesAbsoluteRelativeSubPath() {
        val fs = FakeFileSystem()
        fs.seedDir(base)
        fs.seedDir("/etc", "passwd")
        assertFalse(safeDeleteRecursively(base, "/etc", fs))
        assertTrue(fs.exists("/etc".toPath()), "an absolute 'relative' path must be refused")
    }

    @Test
    fun refusesDotDotEscape() {
        val fs = FakeFileSystem()
        fs.seedDir(base)
        fs.seedDir("/data/data/id.homebase.test/databases", "odin.db")

        val deleted = safeDeleteRecursively(base, "../databases", fs)

        assertFalse(deleted, "must refuse a '..' that escapes the base")
        assertTrue(
            fs.exists("/data/data/id.homebase.test/databases".toPath()),
            "the escaped sibling directory must be untouched",
        )
    }

    @Test
    fun refusesAnyDotDotSegment_evenWhenItStaysUnderBase() {
        val fs = FakeFileSystem()
        fs.seedDir(base)
        fs.seedDir("$base/real", "x.bin")
        // "decoy/../real" resolves back under the base, but we reject any ".." outright.
        assertFalse(safeDeleteRecursively(base, "decoy/../real", fs))
        assertTrue(fs.exists("$base/real".toPath()))
    }

    @Test
    fun refusesRelativeResolvingToBaseItself() {
        val fs = FakeFileSystem()
        fs.seedDir(base, "keep.bin")
        assertFalse(safeDeleteRecursively(base, ".", fs), "must refuse a path that resolves to the base itself")
        assertTrue(fs.exists(base.toPath()))
        assertTrue(fs.exists("$base/keep.bin".toPath()))
    }
}
