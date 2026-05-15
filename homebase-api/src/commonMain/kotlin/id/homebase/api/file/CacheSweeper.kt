package id.homebase.api.file

import co.touchlab.kermit.Logger
import okio.FileSystem

/**
 * Decides what to delete from the app cache directory.
 *
 * **Currently DRY-RUN ONLY** — every "delete" is replaced with a `WOULD DELETE`
 * log line; nothing is actually removed. The intent is to ship this, capture
 * real-device adb logs (`adb logcat -s CacheSweeper:*`), confirm the sweeper
 * targets the right entries — *then* flip the WOULD-DELETE lines to real
 * [safeDeleteRecursively] calls.
 *
 * Two entry points mirror the future real sweep:
 * - [sweepUntracked] — startup reclaim: would delete every entry that isn't one
 *   of the four `-v2` Coil DiskCache directories. Eats the ~4 GB backlog.
 * - [sweepAll] — logout reclaim: would *also* delete the `-v2` caches (the live
 *   DiskCache journal interaction is the nuance to resolve before going live).
 *
 * Special case: if `coil3_disk_cache` is found, it is logged at **ERROR** — its
 * mere existence means something bypassed our configured ImageLoader. This
 * absorbs the role currently scattered across `AppModule`'s logout lambda, the
 * Storage screen's "Clear caches" button, and `probeOrphanCoilDiskCache`.
 */
object CacheSweeper {

    /**
     * Startup-reclaim sweep — would delete everything in [report] that isn't one
     * of [CacheAudit.KNOWN_CACHE_DIRS]. Log-only for now, with one exception:
     * an orphan `coil3_disk_cache` is *actually* deleted (see [act]).
     */
    fun sweepUntracked(report: CacheAudit.Report, fileSystem: FileSystem = systemFileSystem) {
        Logger.i(tag = TAG) {
            "DRY-RUN sweepUntracked: cacheDir=${report.cacheDirPath} " +
                "totalEntries=${report.entries.size} " +
                "wouldDelete=${report.untrackedBytes} bytes (untracked) " +
                "wouldKeep=${report.knownBytes} bytes (tracked Coil caches)"
        }
        for (e in report.entries) act(e, decide(e, SweepMode.UNTRACKED), report.cacheDirPath, fileSystem)
    }

    /**
     * Full sweep (e.g. logout) — would delete everything in [report], including
     * the tracked `-v2` Coil DiskCache directories. Log-only for now, with one
     * exception: an orphan `coil3_disk_cache` is *actually* deleted (see [act]).
     */
    fun sweepAll(report: CacheAudit.Report, fileSystem: FileSystem = systemFileSystem) {
        Logger.i(tag = TAG) {
            "DRY-RUN sweepAll: cacheDir=${report.cacheDirPath} " +
                "totalEntries=${report.entries.size} " +
                "wouldDelete=${report.totalBytes} bytes (incl. tracked Coil caches)"
        }
        for (e in report.entries) act(e, decide(e, SweepMode.ALL), report.cacheDirPath, fileSystem)
    }

    private fun act(e: CacheAudit.Entry, action: SweepAction, baseDir: String, fileSystem: FileSystem) {
        val verb = when (action) {
            SweepAction.KEEP -> "WOULD KEEP  "
            SweepAction.DELETE, SweepAction.ORPHAN_COIL_DELETE -> "WOULD DELETE"
        }
        val line = "$verb ${e.name}${if (e.isDirectory) "/" else ""} — ${e.sizeBytes} bytes [${e.label}]"
        when (action) {
            SweepAction.ORPHAN_COIL_DELETE -> {
                // The one case we actually delete during the dry-run period: orphan-coil is
                // safe (no live DiskCache state behind it), expected to be absent, and
                // "always delete on sight" is the existing scattered behavior we're absorbing.
                // This lets the rogue safeDeleteRecursively("coil3_disk_cache") lines retire.
                Logger.e(tag = TAG) { "ORPHAN COIL DISK CACHE DETECTED — $line (deleting now)" }
                safeDeleteRecursively(baseDir, e.name, fileSystem)
            }
            SweepAction.DELETE -> Logger.w(tag = TAG) { line }
            SweepAction.KEEP -> Logger.i(tag = TAG) { line }
        }
    }

    private const val TAG = "CacheSweeper"
}

internal enum class SweepMode { UNTRACKED, ALL }

internal enum class SweepAction { KEEP, DELETE, ORPHAN_COIL_DELETE }

/**
 * Per-entry decision the sweeper would take. Pure function so it can be
 * unit-tested without log-capturing.
 */
internal fun decide(entry: CacheAudit.Entry, mode: SweepMode): SweepAction = when {
    entry.name == ORPHAN_COIL_DIR_NAME -> SweepAction.ORPHAN_COIL_DELETE
    !entry.known -> SweepAction.DELETE
    mode == SweepMode.ALL -> SweepAction.DELETE
    else -> SweepAction.KEEP
}

internal const val ORPHAN_COIL_DIR_NAME = "coil3_disk_cache"
