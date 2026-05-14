package id.homebase.api.file

import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CacheAuditTest {

    private val cacheDir = "/cache".toPath()

    private fun FakeFileSystem.writeFile(path: String, sizeBytes: Int) {
        val p = path.toPath()
        p.parent?.let { createDirectories(it) }
        write(p) { write(ByteArray(sizeBytes)) }
    }

    @Test
    fun audit_classifiesKnownVsUntracked_andSumsSizes() {
        val fs = FakeFileSystem()
        fs.createDirectories(cacheDir)
        // tracked Coil disk cache: 300 bytes across two files
        fs.writeFile("/cache/homebase-payloads-v2/a", 100)
        fs.writeFile("/cache/homebase-payloads-v2/b", 200)
        // untracked FFmpeg HLS dir: 500 bytes
        fs.writeFile("/cache/hls_abc/index.ts", 500)
        // untracked loose decrypted download: 50 bytes
        fs.writeFile("/cache/myphoto.jpg", 50)

        val report = CacheAudit.audit(cacheDir.toString(), fs)

        assertEquals(300L, report.knownBytes)
        assertEquals(550L, report.untrackedBytes)
        assertEquals(850L, report.totalBytes)
        assertEquals(3, report.entries.size)
        // sorted largest-first
        assertEquals(
            listOf("hls_abc", "homebase-payloads-v2", "myphoto.jpg"),
            report.entries.map { it.name },
        )

        val tracked = report.entries.single { it.name == "homebase-payloads-v2" }
        assertTrue(tracked.known)
        assertTrue(tracked.isDirectory)
        assertEquals(300L, tracked.sizeBytes)

        val hls = report.entries.single { it.name == "hls_abc" }
        assertFalse(hls.known)
        assertTrue(hls.isDirectory)
        assertEquals("FFmpeg HLS segment dir (video send)", hls.label)

        val loose = report.entries.single { it.name == "myphoto.jpg" }
        assertFalse(loose.known)
        assertFalse(loose.isDirectory)
        assertEquals("unknown", loose.label)
    }

    @Test
    fun audit_missingCacheDir_returnsEmptyReport() {
        val fs = FakeFileSystem()
        val report = CacheAudit.audit("/nonexistent".toPath().toString(), fs)
        assertEquals(0L, report.totalBytes)
        assertTrue(report.entries.isEmpty())
    }

    @Test
    fun audit_emptyCacheDir_returnsEmptyReport() {
        val fs = FakeFileSystem()
        fs.createDirectories(cacheDir)
        val report = CacheAudit.audit(cacheDir.toString(), fs)
        assertEquals(0L, report.totalBytes)
        assertTrue(report.entries.isEmpty())
    }

    @Test
    fun directorySizeBytes_emptyAndNested() {
        val fs = FakeFileSystem()
        fs.createDirectories("/cache/empty".toPath())
        assertEquals(0L, fs.directorySizeBytes("/cache/empty".toPath()))

        fs.writeFile("/cache/nest/x/y.bin", 42)
        assertEquals(42L, fs.directorySizeBytes("/cache/nest".toPath()))
    }

    @Test
    fun directorySizeBytes_missingDir_returnsZero() {
        val fs = FakeFileSystem()
        assertEquals(0L, fs.directorySizeBytes("/cache/does-not-exist".toPath()))
    }
}
