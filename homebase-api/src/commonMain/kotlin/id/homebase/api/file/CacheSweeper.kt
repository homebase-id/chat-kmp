package id.homebase.api.file

import co.touchlab.kermit.Logger
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * Decides what to delete from the app cache directory.
 *
 * Two entry points:
 * - [sweepUntracked] — startup reclaim: deletes our own entries except the
 *   tracked Coil DiskCache directories. Eats the cache backlog (FFmpeg scratch,
 *   share temps, leftover pickers, ...).
 * - [sweepAll] — logout reclaim: also deletes the tracked caches.
 *
 * Directories that aren't ours ([CacheAudit.Entry.foreign]) are always kept —
 * see [decide] and [CacheAudit.isOwnedDirectory].
 *
 * Special case: if `coil3_disk_cache` is found it is logged at **ERROR** and
 * deleted — its mere existence means something bypassed our configured
 * ImageLoader. This absorbs the role previously scattered across `AppModule`'s
 * logout lambda, the Storage screen's "Clear caches" button, and
 * `probeOrphanCoilDiskCache`.
 *
 * The shape of the sweep was validated in dry-run mode against an on-device
 * cache audit before this real-delete flip; see commit history.
 */
object CacheSweeper {

    /**
     * Startup-reclaim sweep — deletes everything in [report] that isn't a
     * tracked Coil cache or a foreign directory. Best-effort; per-entry
     * failures are logged by [safeDeleteRecursively] and don't stop the sweep.
     */
    fun sweepUntracked(report: CacheAudit.Report, fileSystem: FileSystem = systemFileSystem) {
        Logger.i(tag = TAG) {
            "sweepUntracked: cacheDir=${report.cacheDirPath} " +
                "totalEntries=${report.entries.size} " +
                "deleting=${report.untrackedBytes} bytes (untracked) " +
                "keepingTracked=${report.knownBytes} bytes (Coil caches) " +
                "keepingForeign=${report.foreignBytes} bytes (not ours)"
        }
        val before = report.totalBytes
        for (e in report.entries) act(e, decide(e, SweepMode.UNTRACKED), report.cacheDirPath, fileSystem)
        logCompletion("sweepUntracked", report.cacheDirPath, before, fileSystem)
    }

    /**
     * Full sweep (e.g. logout) — deletes everything in [report], including the
     * tracked Coil DiskCache directories. Foreign directories are still kept.
     */
    fun sweepAll(report: CacheAudit.Report, fileSystem: FileSystem = systemFileSystem) {
        val deleting = report.untrackedBytes + report.knownBytes
        Logger.i(tag = TAG) {
            "sweepAll: cacheDir=${report.cacheDirPath} " +
                "totalEntries=${report.entries.size} " +
                "deleting=$deleting bytes (untracked + tracked Coil caches) " +
                "keepingForeign=${report.foreignBytes} bytes (not ours)"
        }
        val before = report.totalBytes
        for (e in report.entries) act(e, decide(e, SweepMode.ALL), report.cacheDirPath, fileSystem)
        logCompletion("sweepAll", report.cacheDirPath, before, fileSystem)
    }

    /**
     * Re-measure cacheDir and log a before/after/reclaimed line. Without this
     * the sweep is opaque on real devices: the up-front "deleting=N bytes"
     * is intent, not outcome — a delete failure (permissions, race with another
     * writer) leaves a silent gap. The after-measurement also picks up bytes
     * freed inside KEPT entries (e.g. Coil DiskCache LRU eviction triggered
     * during the same sweep window).
     */
    private fun logCompletion(label: String, cacheDirPath: String, before: Long, fileSystem: FileSystem) {
        val after = runCatching { fileSystem.directorySizeBytes(cacheDirPath.toPath()) }
            .getOrElse {
                Logger.w(tag = TAG, throwable = it) { "$label: post-measure failed" }
                return
            }
        val reclaimed = before - after
        Logger.i(tag = TAG) {
            "$label: complete — before=$before after=$after reclaimed=$reclaimed bytes " +
                "(${humanMB(before)} → ${humanMB(after)}, freed ${humanMB(reclaimed)})"
        }
    }

    private fun humanMB(bytes: Long): String {
        val mb = bytes.toDouble() / (1024.0 * 1024.0)
        // One decimal is plenty — log scanners want approximate magnitude, not precision.
        val rounded = (mb * 10).toLong() / 10.0
        return "${rounded}MB"
    }

    private fun act(e: CacheAudit.Entry, action: SweepAction, baseDir: String, fileSystem: FileSystem) {
        val line = "${e.name}${if (e.isDirectory) "/" else ""} — ${e.sizeBytes} bytes [${e.label}]"
        when (action) {
            SweepAction.ORPHAN_COIL_DELETE -> {
                // Orphan coil dir means something bypassed our configured ImageLoader
                // (we set .diskCache(null)). Logged loudly so the regression is visible.
                Logger.e(tag = TAG) { "ORPHAN COIL DISK CACHE DETECTED — deleting $line" }
                safeDeleteRecursively(baseDir, e.name, fileSystem)
            }
            SweepAction.DELETE -> {
                Logger.i(tag = TAG) { "deleting $line" }
                safeDeleteRecursively(baseDir, e.name, fileSystem)
            }
            SweepAction.KEEP ->
                if (e.foreign) Logger.i(tag = TAG) { "leaving  $line — not ours" }
                else Logger.i(tag = TAG) { "keeping  $line" }
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
    // Wins over every other rule, including the full "logout" sweep.
    entry.foreign -> SweepAction.KEEP
    entry.name == ORPHAN_COIL_DIR_NAME -> SweepAction.ORPHAN_COIL_DELETE
    // LEGACY-ONLY (#842): new encrypted outbox payloads stage in the durable app-data dir
    // (FileOperationsProvider.getOutboxStagingDirectory(), outside cacheDir — this sweeper
    // never sees it). This rule only protects <cacheDir>/outbox-temp files referenced by
    // outbox rows enqueued BEFORE the app update, so they still drain instead of ENOENT-ing;
    // the dual-dir idle reap (OutboxSync.reapIdleOutboxTemps) empties leftovers. Retire the
    // rule once pre-#842 rows can no longer exist (a release or two). The RAW upload-temp dir
    // gets NO such rule: it's disposable pre-encryption scratch that self-heals via the normal
    // untracked sweep below (loss = fail-soft re-pick).
    entry.name == CacheAudit.OUTBOX_TEMP_DIR_NAME -> if (mode == SweepMode.ALL) SweepAction.DELETE else SweepAction.KEEP
    !entry.known -> SweepAction.DELETE
    mode == SweepMode.ALL -> SweepAction.DELETE
    else -> SweepAction.KEEP
}

internal const val ORPHAN_COIL_DIR_NAME = "coil3_disk_cache"
