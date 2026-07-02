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
        assertEquals(0L, report.androidSystemBytes)
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
    fun audit_countsHlsChunkCacheAsKnown() {
        // The dedicated HLS chunk cache (#845) must be a tracked dir — otherwise
        // the startup sweep would wipe warm playback chunks on every launch.
        val fs = FakeFileSystem()
        fs.createDirectories(cacheDir)
        fs.writeFile("/cache/homebase-hls-chunks-v1/chunk", 400)

        val report = CacheAudit.audit(cacheDir.toString(), fs)

        assertEquals(400L, report.knownBytes)
        assertEquals(0L, report.untrackedBytes)
        val entry = report.entries.single { it.name == "homebase-hls-chunks-v1" }
        assertTrue(entry.known)
        assertEquals("tracked Coil disk cache", entry.label)
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

    @Test
    fun audit_flagsAndroidSystemDirs_andLabelsThem() {
        // These four directories live under the app's cacheDir on Android but
        // are managed by the Android platform / WebView / Crashlytics — never
        // by us. The audit must mark them so the CacheSweeper can KEEP them
        // regardless of sweep mode (even on logout / "Clear caches"). Wiping
        // them would, in order: nuke in-app browser cookies + storage (WebView,
        // data), force a slow ART re-compile of WebView native libs
        // (oat_primary), and lose pending crash reports (Crash Reports).
        val fs = FakeFileSystem()
        fs.createDirectories(cacheDir)
        fs.writeFile("/cache/WebView/cookies.bin", 100)
        fs.writeFile("/cache/oat_primary/some.oat", 100)
        fs.writeFile("/cache/data/x.bin", 100)
        fs.writeFile("/cache/Crash Reports/report.json", 100)

        val report = CacheAudit.audit(cacheDir.toString(), fs)

        for (name in listOf("WebView", "oat_primary", "data", "Crash Reports")) {
            val entry = report.entries.single { it.name == name }
            assertTrue(entry.androidSystem, "$name must be flagged as Android system")
            assertFalse(entry.known, "$name must not be classified as a tracked Coil cache")
            assertTrue(entry.label.isNotBlank(), "$name needs a descriptive label")
        }
    }

    @Test
    fun audit_androidSystemBytes_areBucketedSeparately_notLumpedIntoUntracked() {
        // Real-device regression: when only sacred dirs lived alongside the
        // tracked Coil caches, the audit lumped WebView/Crash Reports/etc. into
        // `untrackedBytes`. The sweeper then logged "deleting=N bytes (untracked)"
        // for entries it would actually KEEP, and the post-sweep "freed=0 bytes"
        // line looked like a delete failure. Three disjoint buckets — each entry
        // counted in exactly one — keep the headline numbers honest.
        val fs = FakeFileSystem()
        fs.createDirectories(cacheDir)
        fs.writeFile("/cache/homebase-payloads-v2/x", 100)         // tracked Coil
        fs.writeFile("/cache/WebView/cookies.bin", 200)            // android system
        fs.writeFile("/cache/Crash Reports/r.json", 50)            // android system
        fs.writeFile("/cache/hls_orphan/index.ts", 400)            // untracked

        val report = CacheAudit.audit(cacheDir.toString(), fs)

        assertEquals(100L, report.knownBytes, "tracked Coil bucket")
        assertEquals(400L, report.untrackedBytes, "untracked bucket — must NOT include WebView/Crash Reports")
        assertEquals(250L, report.androidSystemBytes, "android system bucket")
        assertEquals(750L, report.totalBytes, "total = known + untracked + androidSystem")
    }
}
