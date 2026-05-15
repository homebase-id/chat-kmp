package id.homebase.api.client.drives.upload

import co.touchlab.kermit.Logger
import id.homebase.api.client.drives.files.PayloadFile
import id.homebase.api.file.safeDeleteRecursively
import id.homebase.api.file.systemFileSystem
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * For each payload whose `filePath` sits inside an `hls_<uuid>/` directory,
 * recursive-delete that directory via [safeDeleteRecursively]. Best-effort,
 * never throws.
 *
 * Called from:
 *  - the upload SUCCESS path (`DriveUploadProvider.cleanupPayloadTempFiles`),
 *    where the per-file deletion of `index.ts` would otherwise leave the
 *    parent dir + sibling `index.m3u8` (and any `.ts.tmp` FFmpeg leftovers)
 *    behind;
 *  - the Outbox PERMANENT-FAILURE / drop path (`OutboxSync.outboxSend`), so a
 *    message that exhausted retries or hit a terminal error doesn't leak its
 *    HLS scratch dir.
 *
 * Non-HLS payloads (image / audio / non-HLS MP4) have a `filePath` whose parent
 * is *not* `hls_*`; they are silently skipped, leaving the existing per-file
 * cleanup path responsible for them.
 *
 * Multiple payloads may share the same `hls_<uuid>/` parent. The first call
 * removes the dir; subsequent calls fall through `safeDeleteRecursively`'s
 * "missing target → quiet false" path. A small in-call dedup avoids logging
 * the same path more than once per invocation.
 */
internal fun cleanupHlsScratch(
    payloads: List<PayloadFile>?,
    fileSystem: FileSystem = systemFileSystem,
) {
    if (payloads.isNullOrEmpty()) return
    val seen = HashSet<String>()
    for (p in payloads) {
        val parent = runCatching { p.filePath.toPath().parent }.getOrNull() ?: continue
        if (!parent.name.startsWith(HLS_DIR_PREFIX)) continue
        val parentBase = parent.parent?.toString() ?: continue
        val key = "$parentBase|${parent.name}"
        if (!seen.add(key)) continue
        runCatching {
            safeDeleteRecursively(parentBase, parent.name, fileSystem)
        }.onFailure {
            Logger.w(tag = TAG, throwable = it) { "hls scratch cleanup failed for ${parent}" }
        }
    }
}

private const val HLS_DIR_PREFIX = "hls_"
private const val TAG = "HlsScratchCleanup"
