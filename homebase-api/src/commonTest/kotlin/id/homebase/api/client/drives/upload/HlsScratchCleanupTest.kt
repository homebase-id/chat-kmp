package id.homebase.api.client.drives.upload

import id.homebase.api.client.drives.files.PayloadFile
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Cross-platform red-green test for [cleanupHlsScratch]. Uses an okio
 * [FakeFileSystem] so the shared cleanup logic is exercised on every target
 * (JVM / Android / iOS native).
 */
class HlsScratchCleanupTest {

    private val cacheDir = "/data/data/id.homebase.test/cache"

    private fun payload(filePath: String, key: String = "vid"): PayloadFile =
        PayloadFile(key = key, filePath = filePath)

    private fun FakeFileSystem.seedHlsDir(parent: String) {
        createDirectories(parent.toPath())
        write("$parent/index.ts".toPath()) { write(ByteArray(8)) }
        write("$parent/index.m3u8".toPath()) { write(ByteArray(8)) }
    }

    @Test
    fun hlsParent_recursivelyDeleted() {
        val fs = FakeFileSystem()
        fs.createDirectories(cacheDir.toPath())
        val hlsDir = "$cacheDir/hls_abc"
        fs.seedHlsDir(hlsDir)

        cleanupHlsScratch(listOf(payload("$hlsDir/index.ts")), fs)

        assertFalse(fs.exists(hlsDir.toPath()), "hls_<uuid>/ dir must be gone")
        assertTrue(fs.exists(cacheDir.toPath()), "cacheDir itself must survive")
    }

    @Test
    fun nonHlsParent_leftAlone() {
        val fs = FakeFileSystem()
        fs.createDirectories(cacheDir.toPath())
        val nonHls = "$cacheDir/some.bin"
        fs.write(nonHls.toPath()) { write(ByteArray(8)) }

        cleanupHlsScratch(listOf(payload(nonHls)), fs)

        assertTrue(fs.exists(nonHls.toPath()), "non-hls payload file must survive")
        assertTrue(fs.exists(cacheDir.toPath()), "cacheDir must survive")
    }

    @Test
    fun mixedPayloads_onlyHlsDirRemoved() {
        val fs = FakeFileSystem()
        fs.createDirectories(cacheDir.toPath())
        val hlsDir = "$cacheDir/hls_xyz"
        fs.seedHlsDir(hlsDir)
        val nonHls = "$cacheDir/photo.jpg"
        fs.write(nonHls.toPath()) { write(ByteArray(8)) }

        cleanupHlsScratch(
            listOf(
                payload("$hlsDir/index.ts", key = "vid"),
                payload(nonHls, key = "img"),
            ),
            fs,
        )

        assertFalse(fs.exists(hlsDir.toPath()), "hls dir must be gone")
        assertTrue(fs.exists(nonHls.toPath()), "non-hls payload must survive")
    }

    @Test
    fun nullPayloads_noOp() {
        val fs = FakeFileSystem()
        fs.createDirectories(cacheDir.toPath())

        cleanupHlsScratch(null, fs)

        assertTrue(fs.exists(cacheDir.toPath()))
    }

    @Test
    fun emptyPayloads_noOp() {
        val fs = FakeFileSystem()
        fs.createDirectories(cacheDir.toPath())

        cleanupHlsScratch(emptyList(), fs)

        assertTrue(fs.exists(cacheDir.toPath()))
    }

    @Test
    fun multiplePayloadsInSameHlsDir_idempotent() {
        // Two payloads pointing into the same hls_<uuid>/ dir — first
        // call deletes the dir, the second call's safeDeleteRecursively
        // returns false silently (target gone is a no-op). No throw.
        val fs = FakeFileSystem()
        fs.createDirectories(cacheDir.toPath())
        val hlsDir = "$cacheDir/hls_dup"
        fs.seedHlsDir(hlsDir)

        cleanupHlsScratch(
            listOf(
                payload("$hlsDir/index.ts", key = "vid1"),
                payload("$hlsDir/index.m3u8", key = "vid2"),
            ),
            fs,
        )

        assertFalse(fs.exists(hlsDir.toPath()))
        assertTrue(fs.exists(cacheDir.toPath()))
    }
}
