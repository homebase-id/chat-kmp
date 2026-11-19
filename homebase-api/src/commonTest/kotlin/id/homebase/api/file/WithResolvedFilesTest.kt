package id.homebase.api.file

import kotlinx.coroutines.test.runTest
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WithResolvedFilesTest {
    // Copies every "picked/" path; the second one it's asked for fails, as a deleted asset would.
    private class FlakyResolver : OkioFileOperationsProvider(FakeFileSystem(), "/cache") {
        val deleted = mutableListOf<String>()
        private var resolved = 0
        override suspend fun resolveToFilePath(path: String): String {
            if (!path.startsWith("picked/")) return path
            if (++resolved == 2) error("asset gone")
            return "/cache/resolved_$resolved"
        }
        override fun deleteTempFile(path: String): Boolean {
            deleted += path
            return true
        }
    }

    @Test
    fun theCopiesMadeBeforeAResolveFailsAreStillReaped() = runTest {
        val ops = FlakyResolver()
        assertFailsWith<IllegalStateException> { ops.withResolvedFiles(listOf("picked/a", "/plain/b", "picked/c")) { } }
        assertEquals(listOf("/cache/resolved_1"), ops.deleted)
    }

    @Test
    fun everyCopyIsReapedAndPlainPathsAreLeftAlone() = runTest {
        val ops = FlakyResolver()
        val seen = ops.withResolvedFiles(listOf("picked/a", "/plain/b")) { it }
        assertEquals(listOf("/cache/resolved_1", "/plain/b"), seen)
        assertEquals(listOf("/cache/resolved_1"), ops.deleted)
    }
}
