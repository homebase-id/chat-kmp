package id.homebase.api.video

import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Cross-platform red-green test for [deleteHlsKeyMaterial]. Uses an okio
 * [FakeFileSystem], so it exercises the shared cleanup logic on every target —
 * no real FFmpeg, no real video, no device needed. The key files are written
 * by our own `generateHlsKeyInfoFile` with fixed names, so the leak reproduces
 * deterministically just by seeding a directory.
 */
class HlsKeyMaterialTest {

    private val hlsDir = "/cache/hls_abc".toPath()

    private fun FakeFileSystem.seed(vararg names: String) {
        createDirectories(hlsDir)
        for (name in names) {
            write(hlsDir / name) { write(ByteArray(8)) }
        }
    }

    @Test
    fun deletesKeyMaterial_keepsSegments() {
        val fs = FakeFileSystem()
        fs.seed(HLS_KEY_FILE_NAME, HLS_KEY_INFO_FILE_NAME, "index.ts", "index.m3u8")

        val deleted = deleteHlsKeyMaterial(hlsDir.toString(), fs)

        assertEquals(2, deleted)
        assertFalse(fs.exists(hlsDir / HLS_KEY_FILE_NAME), "enc.key must be deleted")
        assertFalse(fs.exists(hlsDir / HLS_KEY_INFO_FILE_NAME), "keyinfo.txt must be deleted")
        assertTrue(fs.exists(hlsDir / "index.ts"), "encrypted segment must be kept")
        assertTrue(fs.exists(hlsDir / "index.m3u8"), "playlist must be kept")
    }

    @Test
    fun missingKeyMaterial_returnsZero_doesNotThrow() {
        val fs = FakeFileSystem()
        fs.seed("index.ts", "index.m3u8")

        val deleted = deleteHlsKeyMaterial(hlsDir.toString(), fs)

        assertEquals(0, deleted)
        assertTrue(fs.exists(hlsDir / "index.ts"))
    }

    @Test
    fun partialKeyMaterial_deletesOnlyWhatExists() {
        val fs = FakeFileSystem()
        fs.seed(HLS_KEY_FILE_NAME, "index.ts")

        val deleted = deleteHlsKeyMaterial(hlsDir.toString(), fs)

        assertEquals(1, deleted)
        assertFalse(fs.exists(hlsDir / HLS_KEY_FILE_NAME))
        assertTrue(fs.exists(hlsDir / "index.ts"))
    }

    @Test
    fun missingDir_returnsZero_doesNotThrow() {
        val fs = FakeFileSystem()
        val deleted = deleteHlsKeyMaterial("/cache/hls_does_not_exist", fs)
        assertEquals(0, deleted)
    }
}
