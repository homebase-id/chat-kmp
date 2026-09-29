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
    fun scratchDir_isDeletedInEveryMode() {
        assertEquals(SweepAction.DELETE, decide(entry(AppCacheDirs.SCRATCH_DIR_NAME), SweepMode.UNTRACKED))
        assertEquals(SweepAction.DELETE, decide(entry(AppCacheDirs.SCRATCH_DIR_NAME), SweepMode.ALL))
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
        val foreign = entry("com.crashlytics.data", foreign = true)
        assertEquals(SweepAction.KEEP, decide(foreign, SweepMode.UNTRACKED))
        assertEquals(SweepAction.KEEP, decide(foreign, SweepMode.ALL))
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
        fs.createDirectories("$cacheDir/hb-scratch/downloads".toPath())
        fs.write("$cacheDir/hb-scratch/downloads/report.pdf".toPath()) { write(ByteArray(8)) }
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
            fs.exists("$cacheDir/hb-scratch".toPath()),
            "app-owned scratch must be deleted",
        )
        assertTrue(
            fs.exists("$cacheDir/resolved_99.jpeg".toPath()),
            "loose files at the cache root are never swept",
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
        fs.createDirectories("$cacheDir/hb-scratch/media-work".toPath())
        fs.write("$cacheDir/hb-scratch/media-work/compressed_x.mp4".toPath()) { write(ByteArray(20_000)) }
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
    fun sweepAll_deletesEverythingOursExceptForeign() {
        val fs = FakeFileSystem()
        val cacheDir = "/data/data/id.homebase.test/cache"
        fs.createDirectories(cacheDir.toPath())
        fs.createDirectories("$cacheDir/homebase-payloads-v2".toPath())
        fs.write("$cacheDir/homebase-payloads-v2/x.bin".toPath()) { write(ByteArray(8)) }
        fs.createDirectories("$cacheDir/hb-scratch".toPath())
        fs.write("$cacheDir/hb-scratch/x.bin".toPath()) { write(ByteArray(8)) }
        fs.write("$cacheDir/resolved_99.jpeg".toPath()) { write(ByteArray(8)) }
        fs.createDirectories("$cacheDir/WebView".toPath())
        fs.write("$cacheDir/WebView/cookies.bin".toPath()) { write(ByteArray(8)) }

        val report = CacheAudit.audit(cacheDir, fs)
        CacheSweeper.sweepAll(report, fs)

        assertFalse(fs.exists("$cacheDir/homebase-payloads-v2".toPath()), "logout sweep deletes tracked too")
        assertFalse(fs.exists("$cacheDir/hb-scratch".toPath()), "logout sweep deletes scratch too")
        assertTrue(fs.exists("$cacheDir/resolved_99.jpeg".toPath()), "logout sweep still keeps loose files")
        assertTrue(fs.exists("$cacheDir/WebView".toPath()), "logout sweep still keeps foreign dirs")
    }

    @Test
    fun foreignDirectories_surviveEverySweep_iosCachesLayout() {
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
        assertFalse(fs.exists("$cacheDir/hls_abc".toPath()), "our own legacy scratch dir is still reclaimed")
        assertTrue(fs.exists("$cacheDir/decrypted download.jpg".toPath()), "loose files are never reclaimed")
    }

    @Test
    fun scratchDir_isClearedIncludingNestedContent() {
        val fs = FakeFileSystem()
        val cacheDir = "/data/data/id.homebase.test/cache"
        fs.createDirectories("$cacheDir/hb-scratch/hls/hls_abc".toPath())
        fs.write("$cacheDir/hb-scratch/hls/hls_abc/index.ts".toPath()) { write(ByteArray(8)) }
        fs.createDirectories("$cacheDir/hb-scratch/share_outbound".toPath())
        fs.write("$cacheDir/hb-scratch/share_outbound/share_1.jpg".toPath()) { write(ByteArray(8)) }

        CacheSweeper.sweepUntracked(CacheAudit.audit(cacheDir, fs), fs)

        assertFalse(fs.exists("$cacheDir/hb-scratch".toPath()))
    }

    @Test
    fun legacyOwnedDirs_areDeletedAtTheRoot() {
        val fs = FakeFileSystem()
        val cacheDir = "/data/data/id.homebase.test/cache"
        val legacy = listOf(
            CacheAudit.UPLOAD_TEMP_DIR_NAME, SHARE_OUTBOUND_DIR_NAME, "share_temp", "hls_abc", "hbvid_res_1", "vts_9",
            ORPHAN_COIL_DIR_NAME,
        )
        for (name in legacy) {
            fs.createDirectories("$cacheDir/$name".toPath())
            fs.write("$cacheDir/$name/f.bin".toPath()) { write(ByteArray(8)) }
        }

        CacheSweeper.sweepUntracked(CacheAudit.audit(cacheDir, fs), fs)

        for (name in legacy) assertFalse(fs.exists("$cacheDir/$name".toPath()), "$name must be swept")
    }

    @Test
    fun looseFilesAndNonOwnedDirs_neverDeleted_inAnyMode() {
        val fs = FakeFileSystem()
        val cacheDir = "/data/data/id.homebase.test/cache"
        val looseFiles = listOf("crash.json", "compressed_clip.mp4", "vault_upload_1.pdf", "hls_looks_like_ours.txt")
        fs.createDirectories(cacheDir.toPath())
        for (name in looseFiles) fs.write("$cacheDir/$name".toPath()) { write(ByteArray(8)) }
        val foreignDirs = listOf("com.crashlytics.data", "Crash Reports", "some-sdk-cache")
        for (name in foreignDirs) {
            fs.createDirectories("$cacheDir/$name".toPath())
            fs.write("$cacheDir/$name/f.bin".toPath()) { write(ByteArray(8)) }
        }

        CacheSweeper.sweepUntracked(CacheAudit.audit(cacheDir, fs), fs)
        CacheSweeper.sweepAll(CacheAudit.audit(cacheDir, fs), fs)

        for (name in looseFiles) assertTrue(fs.exists("$cacheDir/$name".toPath()), "$name must survive")
        for (name in foreignDirs) assertTrue(fs.exists("$cacheDir/$name/f.bin".toPath()), "$name/ must survive")
    }

    @Test
    fun coilCacheDirs_surviveUntrackedSweep_butNotAnythingOutsideScratch() {
        val fs = FakeFileSystem()
        val cacheDir = "/data/data/id.homebase.test/cache"
        for (name in CacheAudit.KNOWN_CACHE_DIRS) {
            fs.createDirectories("$cacheDir/$name".toPath())
            fs.write("$cacheDir/$name/f.bin".toPath()) { write(ByteArray(8)) }
        }

        CacheSweeper.sweepUntracked(CacheAudit.audit(cacheDir, fs), fs)

        for (name in CacheAudit.KNOWN_CACHE_DIRS) assertTrue(fs.exists("$cacheDir/$name/f.bin".toPath()), "$name must survive")
    }
}
