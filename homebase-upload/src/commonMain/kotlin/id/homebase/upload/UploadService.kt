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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import id.homebase.api.common.SecureByteArray
import kotlin.uuid.Uuid

/**
 * The one shared, feature-agnostic upload pipeline (issue #844 Deliverable B). Every media
 * upload — chat, moments, vault, stickers, location, Photos — routes its new-file send through here so
 * the bucket policies live once:
 *
 *   [prepare]: encrypt, or stage a public upload (fail soft on a missing source) → encrypt metadata content when
 *   encrypted → build the request; [queue]: seed the payload cache (a new file; an update seeds after) → durable
 *   outbox enqueue (the success gate) → optimistic local write.
 *
 * It owns the files a request sends until an outbox row takes them, and deletes them on every other way out.
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
     * Encrypt (or stage) + enqueue [spec] and (best-effort) seed the cache and write the optimistic row: [prepare]
     * then [queue]. Returns a typed [UploadOutcome]; never throws for an expected failure (a missing source
     * yields [UploadOutcome.SourceMissing], a failed enqueue yields [UploadOutcome.Failed]).
     */
    suspend fun upload(spec: MediaUploadSpec, scope: CoroutineScope): UploadOutcome {
        // Fail soft: a swept/evicted/revoked source throws here, BEFORE the enqueue, so no doomed outbox row is ever
        // created — the caller re-picks.
        val prepared = try {
            prepare(spec, scope)
        } catch (e: SourceUnavailableException) {
            return UploadOutcome.SourceMissing(listOf(e.path))
        } catch (e: IllegalArgumentException) {
            return UploadOutcome.Failed(e)
        } catch (e: ContentChangedException) {
            return UploadOutcome.Failed(e)
        }
        return queue(prepared)
    }

    /**
     * Steps 1 and 2: encrypt the payloads, or stage a public post's, and build the request. Payloads handed over
     * already encrypted (`isPreEncrypted`, with their IV) become the upload's own from this call on, like the files it
     * makes, and on a throw everything is deleted. Throws [SourceUnavailableException] for a missing source and
     * [IllegalArgumentException] for a public upload that can't be staged or a payload handed over encrypted that
     * can't be sent as it is. Throws [ContentChangedException] when a payload no longer matches
     * [MediaUploadSpec.contentHashes].
     */
    suspend fun prepare(spec: MediaUploadSpec, scope: CoroutineScope): PreparedUpload {
        val encrypted = spec.metadata.isEncrypted
        val fullBundle = sealOrStage(spec.uniqueId, spec.bundle, spec.keyHeader.aesKey, encrypted, scope, spec.contentHashes)
        try {
            val finished = spec.finishMetadata?.let { spec.copy(metadata = it(spec.metadata), finishMetadata = null) } ?: spec
            val sent = if (spec.omitThumbnails) fullBundle.withoutThumbnails() else fullBundle
            val request = UploadFileRequest(
                driveId = spec.driveId,
                keyHeader = spec.keyHeader,
                metadata = if (encrypted) finished.metadata.encryptContent(spec.keyHeader) else finished.metadata,
                transitOptions = spec.transit,
                payloads = sent.payloads,
                thumbnails = sent.thumbnails,
                existingFileIsSuccess = spec.existingFileIsSuccess,
                fileSystemType = spec.requestFileSystemType,
                sealDescriptorContent = spec.sealDescriptorContent,
            )
            return PreparedUpload(finished, request, sent, encryptor)
        } catch (e: Throwable) {
            encryptor.discard(fullBundle)
            throw e
        }
    }

    /** [bundle] encrypted, or staged when the upload isn't. The encryptor deletes only what it made on a throw. */
    private suspend fun sealOrStage(
        uniqueId: Uuid,
        bundle: PayloadBundle?,
        aesKey: SecureByteArray,
        encrypted: Boolean,
        scope: CoroutineScope,
        hashes: ContentHashes? = null,
    ): PayloadBundle {
        val handed = bundle?.payloads.orEmpty().filter { it.isPreEncrypted }
        try {
            require(handed.all { encrypted && it.iv != null }) { "a payload handed over encrypted needs its IV and an encrypted upload" }
            require(encrypted || hashes == null) { "payload hashes are for an encrypted upload" }
            return if (encrypted) encryptor.encryptBundle(uniqueId, bundle, aesKey, scope, hashes) else encryptor.stageBundle(bundle)
        } catch (e: Throwable) {
            if (handed.isNotEmpty()) encryptor.discard(PayloadBundle(handed, emptyList(), emptyList()))
            throw e
        }
    }

    /**
     * Runs [enqueue] and maps its result; null when a row took the files. On any other way out, a cancelled call
     * included, [noRow] runs to the end.
     */
    private suspend inline fun enqueueOrElse(uniqueId: Uuid, noinline noRow: suspend () -> Unit, enqueue: () -> EnqueueResult): UploadOutcome? {
        var queued = false
        try {
            val result = enqueue()
            queued = result is EnqueueResult.Enqueued
            return if (queued) null else result.refused(uniqueId)
        } finally {
            if (!queued) withContext(NonCancellable) { noRow() }
        }
    }

    /**
     * Steps 3 to 5: seed the cache, enqueue, write the optimistic row. Unless a row takes [prepared]'s files, they're
     * deleted and the seed undone, a cancelled call included.
     */
    suspend fun queue(prepared: PreparedUpload): UploadOutcome {
        val spec = prepared.spec
        val sent = prepared.sent
        prepared.claim()
        val noRow: suspend () -> Unit = {
            if (spec.seedCache) {
                try {
                    payloadCacheSeeder.unseed(spec.driveId, spec.optimisticFileId, sent)
                } catch (e: Exception) {
                    Logger.e(throwable = e, tag = TAG) { "upload: unseed failed uniqueId=${spec.uniqueId}" }
                }
            }
            prepared.release()
        }
        enqueueOrElse(spec.uniqueId, noRow) {
            // 3. Seed the payload cache first: once queued, any drain may send the files, delete them and re-key the
            //    cache before a later seed read them. It's under the fresh optimistic fileId, so nothing else reads it
            //    until the row exists.
            if (spec.seedCache) {
                try {
                    payloadCacheSeeder.seed(spec.driveId, spec.optimisticFileId, sent, spec.metadata.isEncrypted)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Logger.e(throwable = e, tag = TAG) { "upload: seed failed (non-fatal) uniqueId=${spec.uniqueId}" }
                }
            }

            // 4. Outbox enqueue — the durable success gate. Once accepted, delivery is guaranteed. Not cancellable: a
            //    cancelled insert may have committed, and then the files are the row's.
            withContext(NonCancellable) {
                if (spec.replace) {
                    outboxSync.replaceEnqueue(prepared.request, spec.priority, spec.dependencyUniqueId, spec.sendNow)
                } else {
                    outboxSync.tryEnqueue(prepared.request, spec.priority, spec.dependencyUniqueId, spec.sendNow, spec.overPeer)
                }
            }
        }?.let { return it }

        // Optimistic mirror of what the server will return; also returned in the outcome so a
        // feature can reuse it for its own optimistic bookkeeping (e.g. the sticker tray).
        val payloadDescriptors = sent.toPayloadDescriptors()

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
                sealOrStage(spec.uniqueId, spec.bundle, spec.keyHeader.aesKey, encrypted = true, scope)
            } catch (e: SourceUnavailableException) {
                return UploadOutcome.SourceMissing(listOf(e.path))
            }
            payloads = encrypted.payloads
            thumbnails = encrypted.thumbnails
            freshlyEncrypted = encrypted
        }

        enqueueOrElse(spec.uniqueId, noRow = { encryptor.discard(PayloadBundle(payloads, thumbnails, emptyList())) }) {
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
            withContext(NonCancellable) {
                if (spec.replace) {
                    outboxSync.replaceEnqueue(request, spec.priority, spec.dependencyUniqueId, spec.sendNow)
                } else {
                    outboxSync.tryEnqueue(request, spec.priority, spec.dependencyUniqueId, spec.sendNow)
                }
            }
        }?.let { return it }

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

    private fun EnqueueResult.refused(uniqueId: Uuid): UploadOutcome = when (this) {
        is EnqueueResult.AlreadyQueued -> UploadOutcome.AlreadyQueued(uniqueId)
        is EnqueueResult.WouldStrandCreate -> UploadOutcome.WouldStrandCreate
        is EnqueueResult.Failed -> UploadOutcome.Failed(cause)
        is EnqueueResult.Enqueued -> error("queued, not refused")
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
