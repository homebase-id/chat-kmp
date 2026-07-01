package id.homebase.upload

import co.touchlab.kermit.Logger
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.client.drives.files.ThumbnailDescriptor
import id.homebase.api.client.drives.upload.UploadFileRequest
import id.homebase.api.file.SourceUnavailableException
import id.homebase.api.sync.database.EnqueueResult
import id.homebase.api.sync.database.OutboxSync
import kotlinx.coroutines.CoroutineScope
import kotlin.io.encoding.Base64
import kotlin.uuid.Uuid

/**
 * The one shared, feature-agnostic upload pipeline (issue #844 Deliverable B). Every media
 * upload — chat, moments, vault, stickers, location — routes its new-file send through here so
 * the bucket policies live once:
 *
 *   encrypt (fail soft on a missing source) → encrypt metadata content → build the request →
 *   durable outbox enqueue (the success gate) → seed the payload cache → optimistic local write.
 *
 * Feature-specific source prep and post-enqueue bookkeeping stay in the feature; this owns only
 * the spine above. Platform specifics sit behind homebase-api primitives (FileOperationsProvider,
 * the encryptor, the outbox), so the same code runs on every target.
 */
class UploadService(
    private val encryptor: PayloadBundleEncryptor,
    private val outboxSync: OutboxSync,
    private val optimisticWriter: OptimisticLocalWriter,
    private val payloadCacheSeeder: PayloadCacheSeeder,
) {
    /**
     * Encrypt + enqueue [spec] and (best-effort) seed the cache and write the optimistic row.
     * Returns a typed [UploadOutcome]; never throws for an expected failure (a missing source
     * yields [UploadOutcome.SourceMissing], a failed enqueue yields [UploadOutcome.Failed]).
     */
    suspend fun upload(spec: MediaUploadSpec, scope: CoroutineScope): UploadOutcome {
        // 1. Encrypt the payloads. Fail soft: a swept/evicted/revoked source throws here, BEFORE
        //    the enqueue below, so no doomed outbox row is ever created — the caller re-picks.
        val encryptedBundle = try {
            encryptor.encryptBundle(spec.uniqueId, spec.bundle, spec.keyHeader.aesKey, scope)
        } catch (e: SourceUnavailableException) {
            return UploadOutcome.SourceMissing(listOf(e.path))
        }

        // 2. Build the request with shared-secret-encrypted metadata content.
        val request = UploadFileRequest(
            driveId = spec.driveId,
            keyHeader = spec.keyHeader,
            metadata = spec.metadata.encryptContent(spec.keyHeader),
            transitOptions = spec.transit,
            payloads = encryptedBundle.payloads,
            thumbnails = encryptedBundle.thumbnails,
        )

        // 3. Outbox enqueue — the durable success gate. Once accepted, delivery is guaranteed.
        val enqueue = if (spec.replace) {
            outboxSync.replaceEnqueue(request, spec.priority, spec.dependencyUniqueId, spec.sendNow)
        } else {
            outboxSync.tryEnqueue(request, spec.priority, spec.dependencyUniqueId, spec.sendNow)
        }
        when (enqueue) {
            is EnqueueResult.Enqueued -> Unit
            is EnqueueResult.AlreadyQueued -> return UploadOutcome.AlreadyQueued(spec.uniqueId)
            is EnqueueResult.WouldStrandCreate -> return UploadOutcome.WouldStrandCreate
            is EnqueueResult.Failed -> return UploadOutcome.Failed(enqueue.cause)
        }

        // Optimistic mirror of what the server will return; also returned in the outcome so a
        // feature can reuse it for its own optimistic bookkeeping (e.g. the sticker tray).
        val payloadDescriptors = encryptedBundle.toPayloadDescriptors()

        // 4. Best-effort: seed the encrypted-payload cache, then write the optimistic row. Seed
        //    BEFORE the write — writeNewFile triggers the bubble's recompose + thumbnail read, so
        //    the cache must already be populated under the optimistic fileId or that read 404s.
        //    Failures here are non-fatal: outbox delivery + sync bring the file back.
        if (spec.seedCache || spec.writeOptimistic) {
            try {
                if (spec.seedCache) {
                    payloadCacheSeeder.seed(spec.driveId, spec.optimisticFileId, encryptedBundle)
                }
                if (spec.writeOptimistic) {
                    optimisticWriter.writeNewFile(
                        driveId = spec.driveId,
                        keyHeader = spec.keyHeader,
                        unecryptedMetadata = spec.metadata,
                        originalRecipientCount = spec.originalRecipientCount,
                        fileSystemType = spec.fileSystemType,
                        payloadDescriptors = payloadDescriptors,
                        fileId = spec.optimisticFileId,
                    )
                }
            } catch (e: Exception) {
                Logger.e(throwable = e, tag = TAG) {
                    "upload: optimistic seed/write failed (non-fatal) uniqueId=${spec.uniqueId}"
                }
            }
        }

        return UploadOutcome.Enqueued(
            uniqueId = spec.uniqueId,
            optimisticFileId = if (spec.writeOptimistic) spec.optimisticFileId else null,
            payloadDescriptors = payloadDescriptors,
        )
    }

    private companion object {
        const val TAG = "UploadService"
    }
}

/**
 * Build the optimistic [PayloadDescriptor]s from an encrypted bundle — the local mirror of what
 * the server will return: per-payload contentType, native thumbnail sizes (bytes are in the
 * seeded cache, not inline), the manual-mode IV, and the embedded preview thumbnail.
 */
private fun PayloadBundle.toPayloadDescriptors(): List<PayloadDescriptor>? =
    payloads.map { payload ->
        PayloadDescriptor(
            key = payload.key,
            contentType = payload.contentType.ifEmpty { null },
            thumbnails = thumbnailDescriptorsFor(payload.key),
            iv = payload.iv?.let { Base64.encode(it) },
            descriptorContent = payload.descriptorContent,
            previewThumbnail = payload.previewThumbnail?.let {
                ThumbnailDescriptor(
                    pixelWidth = it.pixelWidth,
                    pixelHeight = it.pixelHeight,
                    contentType = it.contentType,
                    content = it.content,
                )
            },
        )
    }.ifEmpty { null }
