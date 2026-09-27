package id.homebase.api.file

import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CacheSweeperTest {

    private fun entry(
        name: String,
        known: Boolean = false,
        foreign: Boolean = false,
    ): CacheAudit.Entry =
        CacheAudit.Entry(
            name = name,
            isDirectory = true,
            sizeBytes = 100L,
            known = known,
            foreign = foreign,
            label = "test",
        )

    @Test
    fun untrackedMode_keepsKnownCacheDirs() {
        assertEquals(
            SweepAction.KEEP,
            decide(entry("homebase-payloads-v2", known = true), SweepMode.UNTRACKED),
        )
    }

    @Test
    fun untrackedMode_deletesUnknownEntries() {
        assertEquals(SweepAction.DELETE, decide(entry("hls_abc"), SweepMode.UNTRACKED))
        assertEquals(SweepAction.DELETE, decide(entry("compressed_clip.mp4"), SweepMode.UNTRACKED))
        assertEquals(SweepAction.DELETE, decide(entry("hbvid_preload"), SweepMode.UNTRACKED))
    }

    @Test
    fun allMode_deletesEverythingIncludingTrackedCaches() {
        assertEquals(
            SweepAction.DELETE,
            decide(entry("homebase-payloads-v2", known = true), SweepMode.ALL),
        )
        assertEquals(SweepAction.DELETE, decide(entry("hls_abc"), SweepMode.ALL))
    }

    @Test
    fun hlsChunkCacheDir_isTracked_keptOnStartupSweep_deletedOnLogout() {
        // The dedicated HLS chunk cache (#845) is a tracked Coil LRU like the
        // -v2 dirs: KEEP on the startup / "Clear caches" sweep, DELETE on logout.
        assertEquals(
            SweepAction.KEEP,
            decide(entry("homebase-hls-chunks-v1", known = true), SweepMode.UNTRACKED),
        )
        assertEquals(
            SweepAction.DELETE,
            decide(entry("homebase-hls-chunks-v1", known = true), SweepMode.ALL),
        )
    }

    @Test
    fun outboxTempDir_isKeptOnUntrackedSweep_butDeletedOnLogout() {
        // outbox-temp holds encrypted payloads referenced by pending (incl. offline) outbox rows —
        // a startup / "Clear caches" sweep must NOT delete them (the outbox reaps them on
        // success/drop), but the full logout sweep wipes them.
        assertEquals(
            SweepAction.KEEP,
            decide(entry(CacheAudit.OUTBOX_TEMP_DIR_NAME), SweepMode.UNTRACKED),
        )
        assertEquals(
            SweepAction.DELETE,
            decide(entry(CacheAudit.OUTBOX_TEMP_DIR_NAME), SweepMode.ALL),
        )
    }

    @Test
    fun uploadTempDir_isDisposable_sweptOnEveryMode() {
        // upload-temp holds raw pre-encryption source temps — disposable, so it's reaped on the
        // startup / "Clear caches" sweep (self-healing, can't grow) and on logout.
        assertEquals(
            SweepAction.DELETE,
            decide(entry(CacheAudit.UPLOAD_TEMP_DIR_NAME), SweepMode.UNTRACKED),
        )
        assertEquals(
            SweepAction.DELETE,
            decide(entry(CacheAudit.UPLOAD_TEMP_DIR_NAME), SweepMode.ALL),
        )
    }

    @Test
    fun coil3DiskCache_isAlwaysOrphanCoilDelete_regardlessOfMode() {
        assertEquals(
            SweepAction.ORPHAN_COIL_DELETE,
            decide(entry(ORPHAN_COIL_DIR_NAME), SweepMode.UNTRACKED),
        )
        assertEquals(
            SweepAction.ORPHAN_COIL_DELETE,
            decide(entry(ORPHAN_COIL_DIR_NAME), SweepMode.ALL),
        )
    }

    @Test
    fun foreignDirs_areAlwaysKept_regardlessOfMode() {
        for (name in listOf("WebView", "oat_primary", "data", "Crash Reports", "com.crashlytics.data")) {
            assertEquals(
                SweepAction.KEEP,
                decide(entry(name, foreign = true), SweepMode.UNTRACKED),
                "$name must be KEPT in untracked sweep",
            )
            assertEquals(
                SweepAction.KEEP,
                decide(entry(name, foreign = true), SweepMode.ALL),
                "$name must be KEPT in full sweep too",
            )
        }
    }

    @Test
    fun sweepUntracked_deletes_untrackedEntries_keeps_trackedAndForeign() {
        val fs = FakeFileSystem()
        val cacheDir = "/data/data/id.homebase.test/cache"
        fs.createDirectories(cacheDir.toPath())
        // Untracked: should be reaped.
        fs.createDirectories("$cacheDir/coil3_disk_cache".toPath())
        fs.write("$cacheDir/coil3_disk_cache/some.bin".toPath()) { write(ByteArray(8)) }
        fs.createDirectories("$cacheDir/hls_abc".toPath())
        fs.write("$cacheDir/hls_abc/index.ts".toPath()) { write(ByteArray(8)) }
        fs.write("$cacheDir/resolved_99.jpeg".toPath()) { write(ByteArray(8)) }
        // Tracked Coil cache: kept in untracked sweep.
        fs.createDirectories("$cacheDir/homebase-payloads-v2".toPath())
        fs.write("$cacheDir/homebase-payloads-v2/x.bin".toPath()) { write(ByteArray(8)) }
        // Foreign dir: kept regardless of sweep mode.
        fs.createDirectories("$cacheDir/WebView".toPath())
        fs.write("$cacheDir/WebView/cookies.bin".toPath()) { write(ByteArray(8)) }

        val report = CacheAudit.audit(cacheDir, fs)
        CacheSweeper.sweepUntracked(report, fs)

        assertFalse(
            fs.exists("$cacheDir/coil3_disk_cache".toPath()),
            "orphan coil3_disk_cache must be deleted",
        )
        assertFalse(
            fs.exists("$cacheDir/hls_abc".toPath()),
            "untracked dir must be deleted",
        )
        assertFalse(
            fs.exists("$cacheDir/resolved_99.jpeg".toPath()),
            "untracked file must be deleted",
        )
        assertTrue(
            fs.exists("$cacheDir/homebase-payloads-v2".toPath()),
            "tracked Coil cache dir kept in untracked sweep",
        )
        assertTrue(
            fs.exists("$cacheDir/WebView".toPath()),
            "foreign dir must survive any sweep",
        )
    }

    @Test
    fun sweep_actuallyReclaimsBytes_measuredAfterDelete() {
        // Smoke test for the post-sweep re-measurement: the sweep must
        // actually shrink the cache directory's footprint, not just
        // declare what it intended to delete.
        val fs = FakeFileSystem()
        val cacheDir = "/data/data/id.homebase.test/cache"
        fs.createDirectories(cacheDir.toPath())
        fs.createDirectories("$cacheDir/hls_orphan".toPath())
        fs.write("$cacheDir/hls_orphan/big.ts".toPath()) { write(ByteArray(10_000)) }
        fs.write("$cacheDir/resolved_x.mp4".toPath()) { write(ByteArray(20_000)) }
        fs.createDirectories("$cacheDir/homebase-payloads-v2".toPath())
        fs.write("$cacheDir/homebase-payloads-v2/keep.bin".toPath()) { write(ByteArray(1_000)) }

        val before = fs.directorySizeBytes(cacheDir.toPath())
        assertEquals(31_000L, before)

        val report = CacheAudit.audit(cacheDir, fs)
        CacheSweeper.sweepUntracked(report, fs)

        val after = fs.directorySizeBytes(cacheDir.toPath())
        assertEquals(1_000L, after, "only the tracked Coil cache (1KB) should remain")
    }

    @Test
    fun sweepAll_deletesEverythingExceptForeign() {
        val fs = FakeFileSystem()
        val cacheDir = "/data/data/id.homebase.test/cache"
        fs.createDirectories(cacheDir.toPath())
        fs.createDirectories("$cacheDir/homebase-payloads-v2".toPath())
        fs.write("$cacheDir/homebase-payloads-v2/x.bin".toPath()) { write(ByteArray(8)) }
        fs.write("$cacheDir/resolved_99.jpeg".toPath()) { write(ByteArray(8)) }
        fs.createDirectories("$cacheDir/WebView".toPath())
        fs.write("$cacheDir/WebView/cookies.bin".toPath()) { write(ByteArray(8)) }

        val report = CacheAudit.audit(cacheDir, fs)
        CacheSweeper.sweepAll(report, fs)

        assertFalse(fs.exists("$cacheDir/homebase-payloads-v2".toPath()), "logout sweep deletes tracked too")
        assertFalse(fs.exists("$cacheDir/resolved_99.jpeg".toPath()), "logout sweep deletes untracked too")
        assertTrue(fs.exists("$cacheDir/WebView".toPath()), "logout sweep still keeps foreign dirs")
    }

    @Test
    fun foreignDirectories_surviveEverySweep_iosCachesLayout() {
        // Real iOS Library/Caches from #1716: Crashlytics' pending reports, the dyld closure
        // cache and NSURLCache's bundle-id dir were deleted on every cold start.
        val fs = FakeFileSystem()
        val cacheDir = "/var/mobile/Containers/Data/Application/X/Library/Caches"
        val foreign = listOf("com.crashlytics.data", "com.apple.dyld", "id.homebase.feed", "Some New SDK")
        for (name in foreign) {
            fs.createDirectories("$cacheDir/$name".toPath())
            fs.write("$cacheDir/$name/f.bin".toPath()) { write(ByteArray(8)) }
        }
        fs.createDirectories("$cacheDir/hls_abc".toPath())
        fs.write("$cacheDir/hls_abc/index.ts".toPath()) { write(ByteArray(8)) }
        fs.write("$cacheDir/decrypted download.jpg".toPath()) { write(ByteArray(8)) }

        CacheSweeper.sweepUntracked(CacheAudit.audit(cacheDir, fs), fs)
        CacheSweeper.sweepAll(CacheAudit.audit(cacheDir, fs), fs)

        for (name in foreign) {
            assertTrue(fs.exists("$cacheDir/$name/f.bin".toPath()), "$name/ is not ours and must survive")
        }
        assertFalse(fs.exists("$cacheDir/hls_abc".toPath()), "our own scratch dir is still reclaimed")
        assertFalse(fs.exists("$cacheDir/decrypted download.jpg".toPath()), "loose files are still reclaimed")
    }
}
