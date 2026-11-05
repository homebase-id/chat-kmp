package id.homebase.photobase.backup

import id.homebase.api.file.FileOperationsProvider
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Keeps photos exported for hashing, so encrypting them doesn't export them again. Only a run's
 * first few fit, because hashing runs far ahead of uploads; the rest are exported again when needed.
 * Videos aren't kept: one can outweigh the whole allowance.
 */
class HashedExports(
    private val source: DeviceChangeSource,
    private val fileOps: FileOperationsProvider,
) : DeviceChangeSource by source {
    private class Kept(val exported: ExportedAsset, val bytes: Long)

    private val mutex = Mutex()
    private val kept = mutableMapOf<String, Kept>()
    private var keptBytes = 0L

    /** The caller owns the result and deletes its temporary parts, kept or fresh. */
    override suspend fun export(assetId: String, mediaType: BackupMediaType): ExportedAsset {
        take(assetId)?.let { exported ->
            // upload-temp is swept at startup, which can land while a file waits here.
            if (exported.parts.all { fileOps.getFileSize(it.path) > 0 }) {
                timed(BackupStage.ExportReused) {}
                return exported
            }
            exported.deleteTemporaryParts(fileOps)
        }
        return timed(BackupStage.Export) { source.export(assetId, mediaType) }
    }

    /** Takes ownership of [exported], deleting it when it doesn't fit. */
    suspend fun keep(assetId: String, mediaType: BackupMediaType, exported: ExportedAsset) {
        val worthKeeping = mediaType != BackupMediaType.Video && exported.parts.all { it.isTemporary }
        val bytes = if (worthKeeping) exported.parts.sumOf { fileOps.getFileSize(it.path) } else 0L
        val stored = worthKeeping && mutex.withLock {
            val fits = keptBytes + bytes <= MAX_KEPT_BYTES
            if (fits) {
                kept[assetId] = Kept(exported, bytes)
                keptBytes += bytes
            }
            fits
        }
        if (!stored) exported.deleteTemporaryParts(fileOps)
    }

    suspend fun forget(assetId: String) {
        take(assetId)?.deleteTemporaryParts(fileOps)
    }

    suspend fun forgetAll() = withContext(NonCancellable) {
        val all = mutex.withLock {
            keptBytes = 0L
            kept.values.map { it.exported }.also { kept.clear() }
        }
        all.forEach { it.deleteTemporaryParts(fileOps) }
    }

    private suspend fun take(assetId: String): ExportedAsset? = mutex.withLock {
        kept.remove(assetId)?.let {
            keptBytes -= it.bytes
            it.exported
        }
    }

    private companion object {
        const val MAX_KEPT_BYTES = 256L * 1024 * 1024
    }
}
