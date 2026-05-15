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
        androidSystem: Boolean = false,
    ): CacheAudit.Entry =
        CacheAudit.Entry(
            name = name,
            isDirectory = true,
            sizeBytes = 100L,
            known = known,
            androidSystem = androidSystem,
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
    fun androidSystemDirs_areAlwaysKept_regardlessOfMode() {
        // Sacred set: WebView/, oat_primary/, data/, Crash Reports/. Even on
        // logout (full sweep), we don't touch them — they're owned by the
        // Android platform / WebView / Crashlytics, not the chat app.
        for (name in listOf("WebView", "oat_primary", "data", "Crash Reports")) {
            assertEquals(
                SweepAction.KEEP,
                decide(entry(name, androidSystem = true), SweepMode.UNTRACKED),
                "$name must be KEPT in untracked sweep",
            )
            assertEquals(
                SweepAction.KEEP,
                decide(entry(name, androidSystem = true), SweepMode.ALL),
                "$name must be KEPT in full sweep too",
            )
        }
    }

    @Test
    fun sweep_actuallyDeletes_coil3DiskCache_evenInDryRunMode() {
        // Pinned exception to dry-run: the orphan-coil dir is the one case the sweeper
        // actually deletes now (it absorbs the role of the retired rogue
        // safeDeleteRecursively("coil3_disk_cache") lines). Everything else stays log-only
        // until on-device adb confirms the targets are right.
        val fs = FakeFileSystem()
        val cacheDir = "/data/data/id.homebase.test/cache"
        fs.createDirectories(cacheDir.toPath())
        fs.createDirectories("$cacheDir/coil3_disk_cache".toPath())
        fs.write("$cacheDir/coil3_disk_cache/some.bin".toPath()) { write(ByteArray(8)) }
        fs.createDirectories("$cacheDir/homebase-payloads-v2".toPath())
        fs.createDirectories("$cacheDir/hls_abc".toPath())
        fs.write("$cacheDir/hls_abc/index.ts".toPath()) { write(ByteArray(8)) }

        val report = CacheAudit.audit(cacheDir, fs)
        CacheSweeper.sweepUntracked(report, fs)

        assertFalse(
            fs.exists("$cacheDir/coil3_disk_cache".toPath()),
            "orphan coil3_disk_cache must be actually deleted, not just logged",
        )
        assertTrue(
            fs.exists("$cacheDir/hls_abc".toPath()),
            "non-orphan untracked entries stay on disk during dry-run (log only)",
        )
        assertTrue(
            fs.exists("$cacheDir/homebase-payloads-v2".toPath()),
            "tracked Coil cache dirs are kept",
        )
    }
}
