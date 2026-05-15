package id.homebase.api.file

import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShareOutboundCleanupTest {

    private val cacheDir = "/data/data/id.homebase.test/cache"

    @Test
    fun sweep_removesEntireSubdir_butLeavesOtherCacheEntries() {
        val fs = FakeFileSystem()
        fs.createDirectories(cacheDir.toPath())
        // Two cleartext share temps in the dedicated subdir.
        fs.createDirectories("$cacheDir/$SHARE_OUTBOUND_DIR_NAME".toPath())
        fs.write("$cacheDir/$SHARE_OUTBOUND_DIR_NAME/share_abc.jpg".toPath()) { write(ByteArray(64)) }
        fs.write("$cacheDir/$SHARE_OUTBOUND_DIR_NAME/share_def.mp4".toPath()) { write(ByteArray(128)) }
        // Bystander entries that must survive.
        fs.createDirectories("$cacheDir/homebase-payloads-v2".toPath())
        fs.write("$cacheDir/some_other.bin".toPath()) { write(ByteArray(8)) }

        val result = sweepShareOutbound(cacheDir, fs)

        assertTrue(result, "must report a real delete")
        assertFalse(
            fs.exists("$cacheDir/$SHARE_OUTBOUND_DIR_NAME".toPath()),
            "share_outbound subdir must be gone",
        )
        assertTrue(
            fs.exists("$cacheDir/homebase-payloads-v2".toPath()),
            "tracked Coil cache must survive",
        )
        assertTrue(
            fs.exists("$cacheDir/some_other.bin".toPath()),
            "unrelated cache entries must survive",
        )
        assertTrue(fs.exists(cacheDir.toPath()), "cacheDir itself must survive")
    }

    @Test
    fun sweep_missingSubdir_quietNoOp() {
        val fs = FakeFileSystem()
        fs.createDirectories(cacheDir.toPath())

        val result = sweepShareOutbound(cacheDir, fs)

        assertFalse(result, "no real delete happened, must report false")
        assertTrue(fs.exists(cacheDir.toPath()))
    }

    @Test
    fun sweep_isIdempotent() {
        val fs = FakeFileSystem()
        fs.createDirectories(cacheDir.toPath())
        fs.createDirectories("$cacheDir/$SHARE_OUTBOUND_DIR_NAME".toPath())
        fs.write("$cacheDir/$SHARE_OUTBOUND_DIR_NAME/share_xyz.png".toPath()) { write(ByteArray(8)) }

        assertTrue(sweepShareOutbound(cacheDir, fs))
        assertFalse(sweepShareOutbound(cacheDir, fs), "second call has nothing to delete")
        assertFalse(fs.exists("$cacheDir/$SHARE_OUTBOUND_DIR_NAME".toPath()))
    }

    @Test
    fun sweep_blankCacheDir_refusedByGuards_noThrow() {
        val fs = FakeFileSystem()
        // safeDeleteRecursively's guards refuse blank/short base dirs — we
        // must not blow up, must not delete anything.
        assertFalse(sweepShareOutbound("", fs))
    }
}
