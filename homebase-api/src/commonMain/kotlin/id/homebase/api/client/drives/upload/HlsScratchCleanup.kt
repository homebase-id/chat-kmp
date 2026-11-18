package id.homebase.api.client.drives.upload

import co.touchlab.kermit.Logger
import id.homebase.api.client.drives.files.PayloadFile
import id.homebase.api.file.AppCacheDirs
import id.homebase.api.file.safeDeleteRecursively
import id.homebase.api.file.systemFileSystem
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

/**
 * For each payload whose `filePath` sits inside an `hls_<uuid>/` directory, recursive-delete that directory via
 * [safeDeleteRecursively], once each. Best-effort, never throws. A per-file delete leaves the parent dir, its
 * `index.m3u8` and any `.ts.tmp` leftovers behind; non-HLS payloads are skipped.
 *
 * Public for homebase-upload's `PayloadBundleEncryptionService`, whose bundle can die mid-encryption.
 */
fun cleanupHlsScratch(
    payloads: List<PayloadFile>?,
    fileSystem: FileSystem = systemFileSystem,
) {
    if (payloads.isNullOrEmpty()) return
    for (dir in payloads.mapNotNullTo(LinkedHashSet()) { hlsScratchDir(it.filePath) }) {
        val base = dir.parent ?: continue
        runCatching {
            safeDeleteRecursively(base.toString(), dir.name, fileSystem)
        }.onFailure {
            Logger.w(tag = TAG, throwable = it) { "hls scratch cleanup failed for $dir" }
        }
    }
}

internal fun hlsScratchDir(filePath: String): Path? =
    runCatching { filePath.toPath().parent }.getOrNull()?.takeIf { it.name.startsWith(AppCacheDirs.HLS_DIR_PREFIX) }

private const val TAG = "HlsScratchCleanup"
