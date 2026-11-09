package id.homebase.photobase.backup

import id.homebase.api.file.FileOperationsProvider
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration

/**
 * Keeps photos exported for hashing, so encrypting them doesn't export them again. Hashing waits on
 * [awaitRoom] to stay within the allowance; a photo that still doesn't fit is exported again when
 * needed. Videos aren't kept: one can outweigh the whole allowance.
 */
class HashedExports(
    private val source: DeviceChangeSource,
    private val fileOps: FileOperationsProvider,
) : DeviceChangeSource by source {
    private class Kept(val exported: ExportedAsset, val bytes: Long)

    private val mutex = Mutex()
    private val kept = mutableMapOf<String, Kept>()
    private val keptBytes = MutableStateFlow(0L)

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

    /** False if nothing freed up in [limit], so a stalled upload can't hold hashing up for good. */
    suspend fun awaitRoom(limit: Duration): Boolean =
        withTimeoutOrNull(limit) { keptBytes.first { it < MAX_KEPT_BYTES } } != null

    /** Takes ownership of [exported], deleting it when it doesn't fit. */
    suspend fun keep(assetId: String, mediaType: BackupMediaType, exported: ExportedAsset) {
        val worthKeeping = mediaType != BackupMediaType.Video && exported.parts.all { it.isTemporary }
        val bytes = if (worthKeeping) exported.parts.sumOf { fileOps.getFileSize(it.path) } else 0L
        val stored = worthKeeping && mutex.withLock {
            val fits = keptBytes.value + bytes <= MAX_KEPT_BYTES
            if (fits) {
                kept[assetId] = Kept(exported, bytes)
                keptBytes.value += bytes
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
            keptBytes.value = 0L
            kept.values.map { it.exported }.also { kept.clear() }
        }
        all.forEach { it.deleteTemporaryParts(fileOps) }
    }

    private suspend fun take(assetId: String): ExportedAsset? = mutex.withLock {
        kept.remove(assetId)?.let {
            keptBytes.value -= it.bytes
            it.exported
        }
    }

    private companion object {
        const val MAX_KEPT_BYTES = 256L * 1024 * 1024
    }
}
