package id.homebase.api.file

import co.touchlab.kermit.Logger
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * Read-only inventory of the app cache directory
 * ([FileOperationsProvider.getCacheDirectory]).
 *
 * Motivation: Android's system "Cache" figure — which is exactly
 * `context.cacheDir` — was observed at ~4 GB while the app's Storage Settings
 * screen accounted for only ~710 MB. The gap is scratch files written straight
 * into the cache directory that the app neither tracks, caps, nor evicts:
 * FFmpeg segment/compress output, `hls_*` dirs created on every video send,
 * decrypted downloads, share temp files, the legacy `hbvid_preload` dir, and so
 * on. [CacheAudit] enumerates the *top-level* entries of the cache directory and
 * classifies each as "known" (one of the four capped Coil `DiskCache`
 * directories) or "untracked", so the breakdown is visible both in logs and in
 * the Storage Settings UI.
 *
 * This is diagnostics only — it never deletes anything.
 */
object CacheAudit {

    /**
     * The cache-directory entries the app tracks and caps via Coil's `DiskCache`
     * (LRU-evicted). Everything else under the cache directory is untracked
     * scratch. Keep in sync with the directory names in `DriveFileProviderCached`
     * and `PublicProfileProviderCached`.
     */
    val KNOWN_CACHE_DIRS: Set<String> = setOf(
        "homebase-payloads-v2",
        "homebase-thumbs-v2",
        "homebase-public-profiles-v2",
        "homebase-public-images-v2",
    )

    /**
     * The two upload-pipeline temp subdirectories of the cache dir (#844 PR4). They straddle the
     * #844 durability boundary ("everything before encryption is disposable") and are swept
     * DIFFERENTLY:
     *
     * - [UPLOAD_TEMP_DIR_NAME] — the RAW, pre-encryption source temps ([writeBytesToTempFile]).
     *   Disposable: if one is gone at send time the pipeline fails soft (re-pick). Left "untracked"
     *   so the CacheSweeper reaps it on every startup / "Clear caches" — it self-heals and can't
     *   grow. This is the biggest leak source (picker-resolved inputs).
     * - [OUTBOX_TEMP_DIR_NAME] — the ENCRYPTED, ready-to-transmit payloads
     *   ([writeBytesToOutboxTempFile]), each referenced by an outbox row until the send completes
     *   (a long time when offline). Durable: the CacheSweeper KEEPs it on the startup / "Clear
     *   caches" sweep so an in-flight upload's payload is never deleted mid-flight, and only wipes
     *   it on full logout. It's reaped along the outbox's own lifecycle — on success
     *   (cleanupPayloadTempFiles) and on drop after ~48h (cleanupPayloadsForDroppedRow).
     *
     * Both are counted in the Storage screen total.
     */
    const val UPLOAD_TEMP_DIR_NAME: String = "upload-temp"
    const val OUTBOX_TEMP_DIR_NAME: String = "outbox-temp"

    /**
     * Top-level entries Android places inside our `cacheDir` that are owned by
     * the platform / WebView / a crash reporter — not by us. Wiping any of
     * these is destructive: nukes in-app browser cookies + storage, forces a
     * slow ART recompile of WebView native libs, or loses pending crash
     * reports. The CacheSweeper KEEPS these regardless of mode (even on
     * logout / "Clear caches"). The user-facing way to clear browser state is
     * `WebView.clearCache()`, not deleting files under it.
     */
    val ANDROID_SYSTEM_DIRS: Set<String> = setOf(
        "WebView",
        "oat_primary",
        "data",
        "Crash Reports",
    )

    /** A single top-level entry of the cache directory. */
    data class Entry(
        val name: String,
        val isDirectory: Boolean,
        val sizeBytes: Long,
        /** True when [name] is one of [KNOWN_CACHE_DIRS]. */
        val known: Boolean,
        /** True when [name] is one of [ANDROID_SYSTEM_DIRS] — the sacred set. */
        val androidSystem: Boolean = false,
        /** Best-guess human-readable origin, for the log line. Diagnostic only. */
        val label: String,
    )

    data class Report(
        val cacheDirPath: String,
        /** All top-level entries, sorted largest-first. */
        val entries: List<Entry>,
        /** Bytes in tracked Coil `-v2` caches ([KNOWN_CACHE_DIRS]). */
        val knownBytes: Long,
        /**
         * Bytes in entries that are neither tracked Coil caches nor
         * [ANDROID_SYSTEM_DIRS]. Sweeper-eligible — this is what
         * `sweepUntracked` actually deletes.
         */
        val untrackedBytes: Long,
        /** Bytes in [ANDROID_SYSTEM_DIRS] (sacred — never swept). */
        val androidSystemBytes: Long,
        val totalBytes: Long,
    )

    /**
     * Enumerate the immediate children of [cacheDirPath], recursively sizing
     * each. Never throws — a missing cache directory yields an empty report, and
     * a per-entry failure is logged and skipped.
     */
    fun audit(cacheDirPath: String, fileSystem: FileSystem = systemFileSystem): Report {
        val dir = cacheDirPath.toPath()
        if (!fileSystem.exists(dir)) {
            return Report(cacheDirPath, emptyList(), 0L, 0L, 0L, 0L)
        }

        val children = try {
            fileSystem.list(dir)
        } catch (e: Exception) {
            Logger.w(tag = TAG, throwable = e) { "cannot list cache dir $cacheDirPath" }
            return Report(cacheDirPath, emptyList(), 0L, 0L, 0L, 0L)
        }

        val entries = ArrayList<Entry>(children.size)
        for (child in children) {
            try {
                val meta = fileSystem.metadata(child)
                val isDir = meta.isDirectory
                val size = if (isDir) fileSystem.directorySizeBytes(child) else (meta.size ?: 0L)
                val name = child.name
                entries.add(
                    Entry(
                        name = name,
                        isDirectory = isDir,
                        sizeBytes = size,
                        known = name in KNOWN_CACHE_DIRS,
                        androidSystem = name in ANDROID_SYSTEM_DIRS,
                        label = classify(name),
                    )
                )
            } catch (e: Exception) {
                Logger.w(tag = TAG, throwable = e) { "cannot size cache entry ${child.name}" }
            }
        }
        entries.sortByDescending { it.sizeBytes }

        var known = 0L
        var untracked = 0L
        var androidSystem = 0L
        for (e in entries) {
            // Three disjoint buckets — androidSystem entries must NOT spill into
            // `untracked`, otherwise the sweeper logs "deleting=N (untracked)"
            // for bytes it will actually KEEP and the post-sweep "freed=0" line
            // looks like a delete failure.
            when {
                e.androidSystem -> androidSystem += e.sizeBytes
                e.known -> known += e.sizeBytes
                else -> untracked += e.sizeBytes
            }
        }
        return Report(
            cacheDirPath = cacheDirPath,
            entries = entries,
            knownBytes = known,
            untrackedBytes = untracked,
            androidSystemBytes = androidSystem,
            totalBytes = known + untracked + androidSystem,
        )
    }

    /** Emit [report] to the log under tag [TAG]. */
    fun logReport(report: Report) {
        Logger.i(tag = TAG) {
            "cacheDir=${report.cacheDirPath} total=${report.totalBytes} " +
                "known=${report.knownBytes} untracked=${report.untrackedBytes} " +
                "androidSystem=${report.androidSystemBytes} " +
                "entries=${report.entries.size}"
        }
        for (e in report.entries) {
            val origin = when {
                e.androidSystem -> "android system"
                e.known -> "tracked"
                else -> "untracked"
            }
            val line = "  ${e.name}${if (e.isDirectory) "/" else ""} — ${e.sizeBytes} bytes " +
                "[$origin: ${e.label}]"
            // Untracked entries over the threshold are the ones worth chasing —
            // log them at WARN so they stand out in an `adb logcat` capture.
            // Tracked Coil caches and Android system dirs are never WARN-worthy
            // here: we don't (and won't) sweep them.
            if (!e.known && !e.androidSystem && e.sizeBytes >= LOUD_THRESHOLD_BYTES) {
                Logger.w(tag = TAG) { line }
            } else {
                Logger.i(tag = TAG) { line }
            }
        }
    }

    /**
     * Best-guess origin of a cache entry from its name, for the log line only.
     * This is a *labelling* table, never a deletion list — anything not matched
     * is reported as "unknown" and still counted as untracked.
     */
    private fun classify(name: String): String = when {
        name in KNOWN_CACHE_DIRS -> "tracked Coil disk cache"
        name == "WebView" -> "Android system: WebView (cookies/storage) — sacred"
        name == "oat_primary" -> "Android system: WebView ART/OAT cache — sacred"
        name == "data" -> "Android system: WebView data — sacred"
        name == "Crash Reports" -> "Android system: crash reporter — sacred"
        name == "hbvid_preload" -> "legacy video preload dir"
        name.startsWith("hbvid_res_") -> "streamed MP4 playback temp (deleted on player dispose; swept as backstop)"
        name.startsWith("hbvid_") -> "decrypted video playback scratch"
        name == "coil3_disk_cache" -> "orphan Coil disk cache"
        name == UPLOAD_TEMP_DIR_NAME -> "raw pre-encryption upload temps (disposable — swept every startup)"
        name == OUTBOX_TEMP_DIR_NAME -> "encrypted outbox payload temps (kept until sent/dropped to protect pending sends)"
        name == "homebase-payloads" || name == "homebase-thumbs" ||
            name == "homebase-public-profiles" || name == "homebase-public-images" ->
            "legacy kache dir (pre-v2)"
        name.startsWith("hls_") -> "FFmpeg HLS segment dir (video send)"
        name.startsWith("compressed_") -> "FFmpeg compressed video"
        name.startsWith("ffmpeg-segmented-") -> "FFmpeg segment output"
        name.startsWith("input_hlsdl_") -> "HLS download intermediate"
        name.startsWith("hlsdl_") -> "HLS download output"
        name.startsWith("input_") -> "FFmpeg input cache"
        name.startsWith("thumb0001-") -> "FFmpeg thumbnail"
        name == SHARE_OUTBOUND_DIR_NAME ->
            "share-OUT decrypted Homebase payload (security: sequestered + reaped)"
        name.startsWith("share_") ->
            "share-OUT decrypted Homebase payload (security: legacy, top-level)"
        name.startsWith("resolved_") ->
            "share-IN / picker temp (plaintext input from gallery or sharing app)"
        else -> "unknown"
    }

    private const val TAG = "CacheAudit"

    /** Untracked entries at or above this size are logged at WARN. */
    // Delegates to the render/export boundary (#845): "large enough to be loud
    // about" and "too large to render/cache" are deliberately the same number.
    private val LOUD_THRESHOLD_BYTES = id.homebase.api.client.PayloadSizePolicy.RENDER_LIMIT_BYTES
}
