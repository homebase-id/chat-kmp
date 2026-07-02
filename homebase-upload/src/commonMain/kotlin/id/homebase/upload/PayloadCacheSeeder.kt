package id.homebase.upload

import co.touchlab.kermit.Logger
import id.homebase.api.client.drives.files.DriveFileProvider
import id.homebase.api.file.FileOperationsProvider
import kotlin.uuid.Uuid

/**
 * Seeds the encrypted payload/thumbnail disk cache with the bytes that just went
 * into the outbox, keyed under the optimistic fileId, so a just-sent image shows
 * its sharp thumbnail through the "finalizing" window instead of the blurry
 * embedded preview — and so a failed send's media stays recoverable.
 *
 * Shared by every optimistic-write sender (Chat, Moments, Vault, Stickers). The
 * read path requests the thumbnail at its *native* size (see
 * `selectThumbSize`/`thumbSizesFrom`), so the keys written here — `(driveId,
 * fileId, payloadKey, pixelWidth, pixelHeight)` with `lastModified = null` — match
 * what the display asks for until the server file syncs back (at which point
 * `DriveOutboxUploader.rekeyCacheAfterCreate` moves these entries to the
 * server-assigned fileId).
 *
 * Best-effort: each entry is independent, the cache is LRU-bounded, and a miss
 * only degrades to a re-download / unrecoverable-media retry — so a single
 * failure is logged and skipped, never propagated.
 *
 * Payloads above [MAX_SEED_PAYLOAD_BYTES] are NOT seeded (#947): the thumbnail
 * seed below is the critical one (it's what the bubble renders) and is small;
 * the payload seed is a convenience that avoids a server re-download. The
 * consequence of a skip is the same as an LRU eviction: a failed-send Retry
 * whose create never reached the server degrades to UnrecoverableMedia for
 * that message.
 */
open class PayloadCacheSeeder(
    private val driveFileProvider: DriveFileProvider,
    private val fileOperationsProvider: FileOperationsProvider,
) {
    open suspend fun seed(driveId: Uuid, fileId: Uuid, bundle: PayloadBundle) {
        for (payload in bundle.payloads) {
            try {
                // Probe the size BEFORE readFileBytes — the read buffers the whole
                // payload in RAM, which for a large (single-file HLS) video was a
                // full-video-size allocation on every send (#947).
                val size = fileOperationsProvider.getFileSize(payload.filePath)
                if (size > MAX_SEED_PAYLOAD_BYTES) {
                    Logger.i(tag = TAG) {
                        "seed: skipping payload seed key=${payload.key} size=$size " +
                            "(> $MAX_SEED_PAYLOAD_BYTES) — convenience seed only, thumbs still seeded"
                    }
                    continue
                }
                driveFileProvider.cachePayloadBytesEncrypted(
                    driveId = driveId,
                    fileId = fileId,
                    key = payload.key,
                    bytes = fileOperationsProvider.readFileBytes(payload.filePath),
                    contentType = payload.contentType,
                )
            } catch (e: Exception) {
                Logger.w(throwable = e, tag = TAG) { "seed: payload seed failed (non-fatal) key=${payload.key} fileId=$fileId" }
            }
        }
        for (thumb in bundle.thumbnails) {
            try {
                driveFileProvider.cacheThumbBytesEncrypted(
                    driveId = driveId,
                    fileId = fileId,
                    payloadKey = thumb.key,
                    width = thumb.pixelWidth,
                    height = thumb.pixelHeight,
                    bytes = thumb.thumbnailBytes,
                    contentType = thumb.contentType,
                )
            } catch (e: Exception) {
                Logger.w(throwable = e, tag = TAG) { "seed: thumb seed failed (non-fatal) key=${thumb.key} ${thumb.pixelWidth}x${thumb.pixelHeight} fileId=$fileId" }
            }
        }
    }

    companion object {
        private const val TAG = "PayloadCacheSeeder"

        /**
         * Payload seeding is a convenience (avoids a server re-download for the
         * sender); thumbnails are the critical seed and are small. Above this size
         * the 200 MB LRU payload cache can't usefully retain the entry anyway, and
         * `readFileBytes` would spike RAM by the full payload size. Aligned with
         * `CacheAudit.LOUD_THRESHOLD_BYTES` (the existing "large cache entry" signal).
         */
        const val MAX_SEED_PAYLOAD_BYTES: Long = 50L * 1024 * 1024
    }
}
