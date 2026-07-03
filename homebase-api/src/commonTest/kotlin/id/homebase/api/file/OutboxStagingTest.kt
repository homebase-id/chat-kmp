package id.homebase.api.file

import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The durable outbox staging area (#842): path reservation, promote (file and
 * directory, incl. the cross-filesystem copy fallback), logout wipe, and the
 * write path landing under the staging dir. Runs over a [FakeFileSystem] on all
 * targets — same pattern as [OkioFileOperationsProviderTest].
 */
class OutboxStagingTest {

    private val stagingDir = "/data/app/outbox-staging"

    @Test
    fun createStagingPathIn_createsDirAndReturnsUniqueWritablePaths() {
        val fs = FakeFileSystem()

        val a = createStagingPathIn(stagingDir, "enc", ".encrypted", fs)
        val b = createStagingPathIn(stagingDir, "enc", ".encrypted", fs)

        assertTrue(fs.exists(stagingDir.toPath()), "staging dir must be created")
        assertNotEquals(a, b, "reserved paths must be unique")
        assertTrue(a.startsWith(stagingDir) && a.endsWith(".encrypted"), "path shape: $a")
        assertFalse(fs.exists(a.toPath()), "reservation must NOT write the file — stream writers own that")

        fs.write(a.toPath()) { writeUtf8("ciphertext") }
        assertTrue(fs.exists(a.toPath()))
    }

    @Test
    fun promoteIntoStaging_movesARegularFile() {
        val fs = FakeFileSystem()
        val src = "/cache/video-encrypted-abc.bin".toPath()
        fs.createDirectories(src.parent!!)
        fs.write(src) { writeUtf8("payload") }

        val promoted = promoteIntoStaging(src.toString(), stagingDir, fs)

        assertFalse(fs.exists(src), "source must be gone after promote")
        assertEquals("$stagingDir/video-encrypted-abc.bin", promoted, "base name kept")
        assertEquals("payload", fs.read(promoted.toPath()) { readUtf8() })
    }

    @Test
    fun promoteIntoStaging_movesADirectoryTree() {
        val fs = FakeFileSystem()
        val hls = "/cache/hls_1234".toPath()
        fs.createDirectories(hls)
        fs.write(hls / "index.ts") { writeUtf8("segments") }
        fs.write(hls / "index.m3u8") { writeUtf8("playlist") }

        val promoted = promoteIntoStaging(hls.toString(), stagingDir, fs)

        assertFalse(fs.exists(hls), "source dir must be gone after promote")
        assertEquals("$stagingDir/hls_1234", promoted)
        assertEquals("segments", fs.read(promoted.toPath() / "index.ts") { readUtf8() })
        assertEquals("playlist", fs.read(promoted.toPath() / "index.m3u8") { readUtf8() })
    }

    @Test
    fun promoteIntoStaging_doesNotClobberAnExistingTarget() {
        val fs = FakeFileSystem()
        fs.createDirectories(stagingDir.toPath())
        fs.write("$stagingDir/enc1.bin".toPath()) { writeUtf8("already staged") }
        val src = "/cache/enc1.bin".toPath()
        fs.createDirectories(src.parent!!)
        fs.write(src) { writeUtf8("newcomer") }

        val promoted = promoteIntoStaging(src.toString(), stagingDir, fs)

        assertNotEquals("$stagingDir/enc1.bin", promoted, "same-name target must not be clobbered")
        assertEquals("already staged", fs.read("$stagingDir/enc1.bin".toPath()) { readUtf8() })
        assertEquals("newcomer", fs.read(promoted.toPath()) { readUtf8() })
    }

    @Test
    fun wipeOutboxStaging_removesAllChildrenButKeepsDir() {
        val fs = FakeFileSystem()
        fs.createDirectories(stagingDir.toPath())
        fs.write("$stagingDir/enc1.encrypted".toPath()) { writeUtf8("a") }
        fs.createDirectories("$stagingDir/hls_99".toPath())
        fs.write("$stagingDir/hls_99/index.ts".toPath()) { writeUtf8("b") }

        wipeOutboxStaging(stagingDir, fs)

        assertEquals(emptyList(), fs.list(stagingDir.toPath()), "staging must be empty after wipe")
    }

    @Test
    fun wipeOutboxStaging_onMissingDirIsANoOp() {
        wipeOutboxStaging("/data/app/never-created", FakeFileSystem())
    }

    @Test
    fun writeBytesToOutboxTempFile_landsUnderTheStagingDir() = runTest {
        val fs = FakeFileSystem()
        val ops = OkioFileOperationsProvider(fs, "/tmp/homebase")
        val bytes = byteArrayOf(1, 2, 3, 4)

        val path = ops.writeBytesToOutboxTempFile(bytes, "enc", ".encrypted")

        assertTrue(
            path.startsWith(ops.getOutboxStagingDirectory()),
            "staged write must land in the staging dir: $path",
        )
        assertContentEquals(bytes, ops.readFileBytes(path), "staged bytes must round-trip")
    }

    /**
     * THE #842 durability property: the staging dir sits outside cacheDir, so
     * neither the startup sweep ([CacheSweeper.sweepUntracked]) nor the logout
     * sweep ([CacheSweeper.sweepAll]) — both scoped to the cacheDir audit — can
     * reach a staged payload. Only the explicit logout [wipeOutboxStaging]
     * (asserted above) and the outbox's own lifecycle delete it.
     */
    @Test
    fun stagedFileSurvivesBothCacheSweeps() {
        val fs = FakeFileSystem()
        val cacheDir = "/tmp/homebase"
        fs.createDirectories(cacheDir.toPath())
        // Untracked cache junk that the sweep SHOULD eat — proves the sweep ran.
        fs.write("$cacheDir/hbvid_junk.bin".toPath()) { writeUtf8("junk") }
        val staged = createStagingPathIn(stagingDir, "enc", ".encrypted", fs)
        fs.write(staged.toPath()) { writeUtf8("pending payload") }

        CacheSweeper.sweepUntracked(CacheAudit.audit(cacheDir, fs), fs)
        assertFalse(fs.exists("$cacheDir/hbvid_junk.bin".toPath()), "sweep must actually run")
        assertTrue(fs.exists(staged.toPath()), "staged payload must survive sweepUntracked")

        CacheSweeper.sweepAll(CacheAudit.audit(cacheDir, fs), fs)
        assertTrue(fs.exists(staged.toPath()), "staged payload must survive sweepAll (logout cache sweep)")
    }
}
