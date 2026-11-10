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
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Keeps photos exported for hashing, so encrypting them doesn't export them again. Hashing waits on
 * [awaitRoom] to stay within the allowance; a photo that still doesn't fit is exported again when
 * needed. One video at a time is kept outside the allowance, since one can outweigh all of it, and
 * only for [MAX_VIDEO_WAIT]: videos go through hand-off one at a time anyway.
 */
class HashedExports(
    private val source: DeviceChangeSource,
    private val fileOps: FileOperationsProvider,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) : DeviceChangeSource by source {
    private class Kept(val exported: ExportedAsset, val bytes: Long, val isVideo: Boolean, val since: TimeMark) {
        val isStale: Boolean get() = isVideo && since.elapsedNow() >= MAX_VIDEO_WAIT
    }

    private val mutex = Mutex()
    private val kept = mutableMapOf<String, Kept>()
    private val keptBytes = MutableStateFlow(0L)

    /** The caller owns the result and deletes its temporary parts, kept or fresh. */
    override suspend fun export(assetId: String, mediaType: BackupMediaType): ExportedAsset {
        take(assetId)?.let { held ->
            // upload-temp is swept at startup, which can land while a file waits here.
            if (!held.isStale && held.exported.parts.all { fileOps.getFileSize(it.path) > 0 }) {
                timed(BackupStage.ExportReused) {}
                return held.exported.copy(isHashedCopy = true)
            }
            held.exported.deleteTemporaryParts(fileOps)
        }
        return timed(BackupStage.Export) { source.export(assetId, mediaType) }
    }

    /** False if nothing freed up in [limit], so a stalled upload can't hold hashing up for good. */
    suspend fun awaitRoom(limit: Duration): Boolean =
        withTimeoutOrNull(limit) { keptBytes.first { it < MAX_KEPT_BYTES } } != null

    /** Takes ownership of [exported], deleting it when it doesn't fit. */
    suspend fun keep(assetId: String, mediaType: BackupMediaType, exported: ExportedAsset) {
        val isVideo = mediaType == BackupMediaType.Video
        val worthKeeping = exported.parts.all { it.isTemporary }
        val bytes = if (worthKeeping) exported.parts.sumOf { fileOps.getFileSize(it.path) } else 0L
        var evicted: ExportedAsset? = null
        val stored = worthKeeping && mutex.withLock {
            if (isVideo) {
                kept.entries.firstOrNull { it.value.isStale }?.let { stale ->
                    kept.remove(stale.key)
                    evicted = stale.value.exported
                }
                val fits = bytes <= MAX_KEPT_VIDEO_BYTES && kept.values.none { it.isVideo }
                if (fits) kept[assetId] = Kept(exported, bytes, isVideo = true, timeSource.markNow())
                fits
            } else {
                val fits = keptBytes.value + bytes <= MAX_KEPT_BYTES
                if (fits) {
                    kept[assetId] = Kept(exported, bytes, isVideo = false, timeSource.markNow())
                    keptBytes.value += bytes
                }
                fits
            }
        }
        evicted?.deleteTemporaryParts(fileOps)
        if (!stored) exported.deleteTemporaryParts(fileOps)
    }

    suspend fun forget(assetId: String) {
        take(assetId)?.exported?.deleteTemporaryParts(fileOps)
    }

    suspend fun forgetAll() = withContext(NonCancellable) {
        val all = mutex.withLock {
            keptBytes.value = 0L
            kept.values.map { it.exported }.also { kept.clear() }
        }
        all.forEach { it.deleteTemporaryParts(fileOps) }
    }

    private suspend fun take(assetId: String): Kept? = mutex.withLock {
        kept.remove(assetId)?.also { if (!it.isVideo) keptBytes.value -= it.bytes }
    }

    private companion object {
        const val MAX_KEPT_BYTES = 256L * 1024 * 1024
        const val MAX_KEPT_VIDEO_BYTES = 4L * 1024 * 1024 * 1024
        val MAX_VIDEO_WAIT: Duration = 30.minutes
    }
}
