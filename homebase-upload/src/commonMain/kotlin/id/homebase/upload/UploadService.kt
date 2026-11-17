package id.homebase.upload

import co.touchlab.kermit.Logger
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.client.drives.files.PayloadFile
import id.homebase.api.client.drives.files.ThumbnailDescriptor
import id.homebase.api.client.drives.files.ThumbnailFile
import id.homebase.api.client.drives.upload.FileUpdateInstructionSet
import id.homebase.api.client.drives.upload.UpdateFileByUniqueIdRequest
import id.homebase.api.client.drives.upload.UpdateManifest
import id.homebase.api.client.drives.upload.UploadFileRequest
import id.homebase.api.file.SourceUnavailableException
import id.homebase.api.serialization.Base64Bytes
import id.homebase.api.sync.database.EnqueueResult
import id.homebase.api.sync.database.OutboxSync
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope

/**
 * The one shared, feature-agnostic upload pipeline (issue #844 Deliverable B). Every media
 * upload — chat, moments, vault, stickers, location — routes its new-file send through here so
 * the bucket policies live once:
 *
 *   encrypt, or stage a public upload (fail soft on a missing source) → encrypt metadata content when
 *   encrypted → build the request → seed the payload cache (a new file; an update seeds after) → durable
 *   outbox enqueue (the success gate) → optimistic local write.
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
     * The request [upload] would queue, for a transport of its own (an iOS background session): steps 1 and 2 alone.
     * The caller owns its payload files. Throws [SourceUnavailableException] for a missing source.
     */
    suspend fun buildRequest(spec: MediaUploadSpec, scope: CoroutineScope): UploadFileRequest = prepare(spec, scope).first

    private suspend fun prepare(spec: MediaUploadSpec, scope: CoroutineScope): Pair<UploadFileRequest, PayloadBundle> {
        // 1. Encrypt the payloads, or stage a public post's as they are.
        val encrypted = spec.metadata.isEncrypted
        val fullBundle = if (encrypted) {
            encryptor.encryptBundle(spec.uniqueId, spec.bundle, spec.keyHeader.aesKey, scope)
        } else {
            encryptor.stageBundle(spec.bundle)
        }
        val sentBundle = if (spec.omitThumbnails) fullBundle.withoutThumbnails() else fullBundle

        // 2. Build the request, its metadata content encrypted when the upload is.
        val request = UploadFileRequest(
            driveId = spec.driveId,
            keyHeader = spec.keyHeader,
            metadata = if (encrypted) spec.metadata.encryptContent(spec.keyHeader) else spec.metadata,
            transitOptions = spec.transit,
            payloads = sentBundle.payloads,
            thumbnails = sentBundle.thumbnails,
            existingFileIsSuccess = spec.existingFileIsSuccess,
            fileSystemType = spec.requestFileSystemType,
            sealDescriptorContent = spec.sealDescriptorContent,
        )
        return request to sentBundle
    }

    /**
     * Encrypt (or stage) + enqueue [spec] and (best-effort) seed the cache and write the optimistic row.
     * Returns a typed [UploadOutcome]; never throws for an expected failure (a missing source
     * yields [UploadOutcome.SourceMissing], a failed enqueue yields [UploadOutcome.Failed]).
     */
    suspend fun upload(spec: MediaUploadSpec, scope: CoroutineScope): UploadOutcome {
        // 1 and 2. Fail soft: a swept/evicted/revoked source throws here, BEFORE the enqueue below, so no doomed
        //    outbox row is ever created — the caller re-picks.
        val (request, sentBundle) = try {
            prepare(spec, scope)
        } catch (e: SourceUnavailableException) {
            return UploadOutcome.SourceMissing(listOf(e.path))
        } catch (e: IllegalArgumentException) {
            return UploadOutcome.Failed(e)
        }
        val encrypted = spec.metadata.isEncrypted

        // 3. Seed the payload cache first: once queued, any drain may send the files, delete them and re-key the cache
        //    before a later seed read them. It's under the fresh optimistic fileId, so nothing else reads it until the
        //    row exists; undone if the enqueue doesn't take.
        if (spec.seedCache) {
            try {
                payloadCacheSeeder.seed(spec.driveId, spec.optimisticFileId, sentBundle, encrypted)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e(throwable = e, tag = TAG) { "upload: seed failed (non-fatal) uniqueId=${spec.uniqueId}" }
            }
        }

        // 4. Outbox enqueue — the durable success gate. Once accepted, delivery is guaranteed.
        val enqueue = if (spec.replace) {
            outboxSync.replaceEnqueue(request, spec.priority, spec.dependencyUniqueId, spec.sendNow)
        } else {
            outboxSync.tryEnqueue(request, spec.priority, spec.dependencyUniqueId, spec.sendNow, spec.overPeer)
        }
        if (enqueue !is EnqueueResult.Enqueued) {
            if (spec.seedCache) payloadCacheSeeder.unseed(spec.driveId, spec.optimisticFileId, sentBundle)
            if (!encrypted) encryptor.discardStaged(sentBundle)
        }
        when (enqueue) {
            is EnqueueResult.Enqueued -> Unit
            is EnqueueResult.AlreadyQueued -> return UploadOutcome.AlreadyQueued(spec.uniqueId)
            is EnqueueResult.WouldStrandCreate -> return UploadOutcome.WouldStrandCreate
            is EnqueueResult.Failed -> return UploadOutcome.Failed(enqueue.cause)
        }

        // Optimistic mirror of what the server will return; also returned in the outcome so a
        // feature can reuse it for its own optimistic bookkeeping (e.g. the sticker tray).
        val payloadDescriptors = sentBundle.toPayloadDescriptors()

        // 5. Best-effort: the optimistic row, whose recompose reads the cache seeded above. Outbox delivery and sync
        //    bring the file back if it fails.
        if (spec.writeOptimistic) {
            try {
                optimisticWriter.writeNewFile(
                    driveId = spec.driveId,
                    keyHeader = spec.keyHeader,
                    unecryptedMetadata = spec.optimisticMetadata ?: spec.metadata,
                    originalRecipientCount = spec.originalRecipientCount,
                    fileSystemType = spec.fileSystemType,
                    payloadDescriptors = payloadDescriptors,
                    fileId = spec.optimisticFileId,
                )
            } catch (e: Exception) {
                Logger.e(throwable = e, tag = TAG) {
                    "upload: optimistic write failed (non-fatal) uniqueId=${spec.uniqueId}"
                }
            }
        }

        return UploadOutcome.Enqueued(
            uniqueId = spec.uniqueId,
            optimisticFileId = if (spec.writeOptimistic) spec.optimisticFileId else null,
            payloadDescriptors = payloadDescriptors,
        )
    }

    /**
     * Update an existing file (`UpdateFileByUniqueIdRequest`). The shared counterpart to [upload]
     * for the non-create paths: header-only edits, appending/overwriting payloads, delete-only
     * manifests, and re-attaching a file's existing already-encrypted payloads. Same fail-soft +
     * [UploadOutcome] contract; the optimistic step is `writeUpdate` (not `writeNewFile`).
     */
    suspend fun updateFile(spec: MediaUpdateSpec, scope: CoroutineScope): UploadOutcome {
        // Resolve the payloads to attach: reuse the caller's already-encrypted payloads as-is, or
        // encrypt the plaintext bundle (fail soft on a missing source, BEFORE any enqueue).
        val payloads: List<PayloadFile>
        val thumbnails: List<ThumbnailFile>
        // The freshly-encrypted bundle, kept ONLY on the encrypt path so the optimistic step below
        // can write its payload descriptors through + seed its bytes under the existing fileId (see
        // MediaUpdateSpec.fileId / issue #927). Null on the pre-encrypted (add-recipients) path,
        // where the reused bytes/IVs are unchanged and the existing descriptors stay valid.
        var freshlyEncrypted: PayloadBundle? = null
        if (spec.preEncryptedPayloads != null) {
            payloads = spec.preEncryptedPayloads
            thumbnails = spec.preEncryptedThumbnails ?: emptyList()
        } else {
            val encrypted = try {
                encryptor.encryptBundle(spec.uniqueId, spec.bundle, spec.keyHeader.aesKey, scope)
            } catch (e: SourceUnavailableException) {
                return UploadOutcome.SourceMissing(listOf(e.path))
            }
            payloads = encrypted.payloads
            thumbnails = encrypted.thumbnails
            freshlyEncrypted = encrypted
        }

        val request = UpdateFileByUniqueIdRequest(
            driveId = spec.driveId,
            uniqueId = spec.uniqueId,
            keyHeader = spec.keyHeader,
            instructions = FileUpdateInstructionSet(
                transferIv = spec.transferIv,
                locale = spec.locale,
                recipients = spec.recipients,
                manifest = UpdateManifest.build(
                    payloads = payloads.ifEmpty { null },
                    toDeletePayloads = spec.toDeletePayloads,
                    thumbnails = thumbnails.ifEmpty { null },
                    generatePayloadIv = spec.generatePayloadIv,
                ),
                useAppNotification = spec.useAppNotification,
                appNotificationOptions = spec.appNotificationOptions,
            ),
            metadata = spec.metadata.encryptContent(spec.keyHeader),
            payloads = payloads.ifEmpty { null },
            thumbnails = thumbnails.ifEmpty { null },
        )

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

        // When the caller gave us the existing fileId AND we just encrypted a COMPLETE new payload
        // set (a full-state overwrite — a vault note edit, not an incremental append), write the new
        // payload descriptors THROUGH and seed the encrypted bytes under that fileId. Without this the
        // optimistic write kept the OLD descriptors, so the local store never reflected the new body
        // and a read-back before the send decrypted stale server bytes with the new IV (issue #927).
        // Otherwise (header-only edit, incremental append, add-recipients) pass null → writeUpdate
        // preserves the existing payloads. Seed + write are best-effort; outbox delivery is the gate.
        val writeThrough = freshlyEncrypted?.takeIf { spec.fileId != null && it.payloads.isNotEmpty() }
        val optimisticDescriptors = writeThrough?.toPayloadDescriptors()
        if (spec.writeOptimistic) {
            try {
                if (writeThrough != null && spec.seedCache) {
                    payloadCacheSeeder.seed(spec.driveId, spec.fileId!!, writeThrough, encrypted = true)
                }
                optimisticWriter.writeUpdate(spec.driveId, spec.keyHeader, spec.metadata, optimisticDescriptors)
            } catch (e: Exception) {
                Logger.e(throwable = e, tag = TAG) {
                    "updateFile: optimistic seed/writeUpdate failed (non-fatal) uniqueId=${spec.uniqueId}"
                }
            }
        }

        return UploadOutcome.Enqueued(spec.uniqueId, optimisticFileId = null, payloadDescriptors = optimisticDescriptors)
    }

    private companion object {
        const val TAG = "UploadService"
    }
}

/**
 * The optimistic row's descriptors for [this], encrypted or plaintext: the local mirror of what the server will
 * return, with the native thumbnail sizes (their bytes are in the seeded cache, not inline).
 */
internal fun PayloadBundle.toPayloadDescriptors(): List<PayloadDescriptor>? =
    payloads.map { payload ->
        PayloadDescriptor(
            key = payload.key,
            contentType = payload.contentType.ifEmpty { null },
            thumbnails = thumbnailDescriptorsFor(payload.key),
            iv = payload.iv?.let { Base64Bytes(it) },
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
