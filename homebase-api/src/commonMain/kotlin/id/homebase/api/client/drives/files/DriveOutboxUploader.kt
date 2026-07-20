package id.homebase.api.client.drives.files

import co.touchlab.kermit.Logger
import id.homebase.api.client.ClientException
import id.homebase.api.client.KeyHeader
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import id.homebase.api.client.NotFoundException
import id.homebase.api.client.OdinClientErrorCode
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.upload.CreateFileResult
import id.homebase.api.client.drives.upload.DriveUploadProvider
import id.homebase.api.client.drives.upload.PayloadUploadReceipt
import id.homebase.api.client.drives.upload.FileUpdateInstructionSet
import id.homebase.api.client.drives.upload.LocalAppData
import id.homebase.api.client.drives.upload.UpdateFileByUniqueIdRequest
import id.homebase.api.client.drives.upload.UpdateLocalAppdataContentOutboxRequest
import id.homebase.api.client.drives.upload.UpdateLocalMetadataTagsOutboxRequest
import id.homebase.api.client.drives.upload.UpdateLocale
import id.homebase.api.client.drives.upload.UpdateManifest
import id.homebase.api.client.drives.upload.UploadFileRequest
import id.homebase.api.client.drives.upload.validateForUpload
import id.homebase.api.crypto.ByteArrayUtil
import id.homebase.api.client.drives.files.reactions.DriveFileGroupReactionProvider
import id.homebase.api.client.drives.files.reactions.ToggleReactionOutboxRequest
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.sync.database.DatabaseManager
import id.homebase.api.sync.database.Outbox
import id.homebase.api.sync.database.OutboxUploader
import kotlin.uuid.Uuid

class DriveOutboxUploader(
    private val driveUploadProvider: DriveUploadProvider,
    private val fileProvider: DriveFileProvider,
    private val operationsProvider: DriveFileOperationsProvider,
    private val reactionProvider: DriveFileGroupReactionProvider,
    private val databaseManager: DatabaseManager,
    private val credentialsManager: CredentialsManager,
) : OutboxUploader {

    override suspend fun upload(
        outboxRecord: Outbox,
        eventBus: EventBus
    ) {
        try {
            when (outboxRecord.uploadType) {
                UploadNewFile -> uploadNewFile(outboxRecord, eventBus)
                UpdateFile -> updateFile(outboxRecord, eventBus)
                DeleteFile -> deleteFile(outboxRecord)
                UpdateLocalMetadataTags -> updateLocalMetadataTags(outboxRecord)
                UpdateLocalMetadataContent -> updateLocalMetadataContent(outboxRecord)
                SendReadReceiptByFileIds -> sendReadReceiptByFileIds(outboxRecord)
                ToggleReaction -> toggleReaction(outboxRecord)
                DeleteFilesByGroupId -> deleteFilesByGroupId(outboxRecord)
            }
        } catch (e: ClientException) {
            // Always rethrow — never swallow a terminal error by returning
            // normally. Doing so used to make OutboxSync take the success path
            // and emit ItemCompleted for an upload the server rejected (the
            // bubble showed *sent* for a message that never landed). All
            // permanent-vs-retryable classification lives in
            // [classifyPermanentFailure]; OutboxSync drops permanent failures
            // with an honest OutboxItemDropped.
            Logger.e(
                "$TAG upload: failing outbox item ${outboxRecord.uniqueId} " +
                        "uploadType=${outboxRecord.uploadType} status=${e.status} " +
                        "errorCode=${e.errorCode} message=${e.message}"
            )
            throw e
        }
    }

    private suspend fun uploadNewFile(outboxRecord: Outbox, eventBus: EventBus) {
        val request = OdinSystemSerializer.deserialize<UploadFileRequest>(outboxRecord.json.decodeToString())
        // Pre-flight: catch oversize thumbs / payload-key typos / etc.
        // before the network call so the row drops on attempt 1 via the
        // existing isPermanentFailure path instead of after a wasted
        // round-trip. See UploadValidation.kt.
        request.validateForUpload()
        Logger.d("$TAG uploadNewFile: uniqueId=${request.metadata.appData.uniqueId} fileType=${request.metadata.appData.fileType} driveId=${request.driveId}")
        try {
            // Throttle to whole-percent steps via WholePercentProgressGate: the HTTP client
            // fires onProgress per chunk, so an un-gated multi-MB upload emitted hundreds of
            // suspending ItemProgress events at the same integer percent, flooding the
            // EventBus buffer (extraBufferCapacity=10) and parking the producer against ~32
            // subscribers. The gate caps it at ≤101 emits (0..100) per upload and is local to
            // this call, so concurrent uploads never race on it.
            val gate = WholePercentProgressGate()
            val result = driveUploadProvider.uploadFile(request, onProgress = { sent, total ->
                gate.admit(percentOf(sent, total))?.let { pct ->
                    eventBus.emit(BackendEvent.OutboxEvent.ItemProgress(outboxRecord.driveId, outboxRecord.uniqueId, pct, sent))
                }
            })
            val rStatus = result?.recipientStatus
            if (rStatus.isNullOrEmpty()) {
                Logger.d(
                    "$TAG uploadNewFile: success uniqueId=${request.metadata.appData.uniqueId} " +
                            "fileId=${result?.fileId} (no recipients)"
                )
            } else {
                Logger.i(
                    "$TAG uploadNewFile: success uniqueId=${request.metadata.appData.uniqueId} " +
                            "fileId=${result.fileId} " +
                            "recipientStatus=${rStatus.entries.joinToString { "${it.key}=${it.value}" }}"
                )
            }
            // Best-effort, MUST never throw: an exception after a successful
            // upload would read as upload failure to OutboxSync and re-send
            // the file.
            if (result != null) {
                runCatching { rekeyCacheAfterCreate(request, result) }.onFailure {
                    Logger.w("$TAG uploadNewFile: cache rekey failed (non-fatal) uniqueId=${request.metadata.appData.uniqueId}", it)
                }
            }
        // region Recovery: missing conversation file
        // If the server already has a file with this uniqueId (e.g. stale/archived
        // from a previous install), convert the failed UploadNewFile into an
        // UpdateFileByUniqueId so the client's fresh content lands on the server.
        } catch (e: ClientException) {
            // Route through retryAsUpdate whenever the server reports the uniqueId
            // already exists. The proper errorCode is ExistingFileWithUniqueId, but
            // the server also reports this condition as errorCode=UnhandledScenario
            // with title "File already exists with ClientUniqueId: [...]" — the
            // title match is a defensive fallback for that case. Without it the
            // recovery path is skipped and the outbox retries for 20 attempts.
            val isExistingFileConflict = e.status == 400 && (
                    e.errorCode == OdinClientErrorCode.ExistingFileWithUniqueId ||
                            e.message?.startsWith("File already exists with ClientUniqueId") == true)
            if (isExistingFileConflict) {
                Logger.w(
                    "$TAG uploadNewFile: uniqueId=${request.metadata.appData.uniqueId} " +
                            "got ExistingFileWithUniqueId (server already has this file) — " +
                            "converting to update so client content is not lost. " +
                            "fileType=${request.metadata.appData.fileType} error=${e.message}"
                )
                retryAsUpdate(request, outboxRecord, eventBus)
                return
            }
            Logger.e("$TAG uploadNewFile: failed uniqueId=${request.metadata.appData.uniqueId} status=${e.status} errorCode=${e.errorCode} message=${e.message}")
            throw e
        }
        // endregion
    }

    /**
     * Move the file's seeded payload-cache entries from the optimistic
     * (client-minted) fileId to the server-assigned one, now that the upload
     * response tells us both the new fileId and — via [CreateFileResult.payloads]
     * receipts — each payload's server `lastModified` (the version segment of
     * the thumbnail cache key). Doing this here, rather than at sync-back,
     * works for every drive (chat, Vault, Moments), needs no UI state, and
     * lands before the synced file re-renders under the new fileId.
     *
     * The old fileId comes from the local optimistic record, which still holds
     * it at this moment (sync-back hasn't replaced the row yet). No local
     * record, or one already carrying the server fileId → nothing to move.
     * On servers that pre-date the receipts field, thumbs are skipped (their
     * target keys need the server lastModified) and only payload entries move;
     * the orphaned thumb seeds age out via LRU.
     */
    private suspend fun rekeyCacheAfterCreate(request: UploadFileRequest, result: CreateFileResult) {
        val uniqueId = request.metadata.appData.uniqueId ?: return
        val identityId = credentialsManager.requireActiveCredentials().getIdentityId()
        val local = databaseManager.driveMainIndex
            .selectHomebaseFileByUnique(identityId, request.driveId, uniqueId) ?: return
        if (local.fileId == result.fileId) return

        val descriptors = stampDescriptorsWithReceipts(local.fileMetadata.payloads.orEmpty(), result.payloads)
        if (descriptors.isEmpty()) return

        fileProvider.rekeyCachedFile(request.driveId, local.fileId, result.fileId, descriptors)
        Logger.d(
            "$TAG rekeyCacheAfterCreate: moved cache entries uniqueId=$uniqueId " +
                    "old=${local.fileId} new=${result.fileId} receipts=${result.payloads.size}"
        )
    }

    // region Recovery: missing conversation file — retry UploadNewFile as update
    /**
     * When an UploadNewFile fails with ExistingFileWithUniqueId, the server already
     * has a file with this uniqueId (e.g. a stale/archived version). Convert the
     * original upload request into an UpdateFileByUniqueId so the client's fresh
     * content (participants, keys) lands on the server instead of being discarded.
     */
    private suspend fun retryAsUpdate(
        original: UploadFileRequest,
        outboxRecord: Outbox,
        eventBus: EventBus
    ) {
        val uniqueId = original.metadata.appData.uniqueId
            ?: error("Cannot retry as update — no uniqueId in metadata")

        Logger.d("$TAG retryAsUpdate: fetching server file header for uniqueId=$uniqueId driveId=${original.driveId}")
        val serverFile = fileProvider.getFileHeaderByUid(original.driveId, uniqueId)
        val versionTag = serverFile?.fileMetadata?.versionTag

        if (serverFile == null) {
            Logger.e("$TAG retryAsUpdate: server returned 404 for uniqueId=$uniqueId — file does not exist on server despite ExistingFileWithUniqueId error. Aborting retry.")
            error("retryAsUpdate: server file not found for uniqueId=$uniqueId")
        }

        Logger.d(
            "$TAG retryAsUpdate: server file found for uniqueId=$uniqueId " +
                    "versionTag=$versionTag " +
                    "fileType=${serverFile.fileMetadata.appData.fileType} " +
                    "fileState=${serverFile.fileState} " +
                    "recipients=${original.transitOptions?.recipients?.size ?: 0}"
        )

        // Lost-ack recovery: the original UploadNewFile already landed on the
        // server (that's *why* it reports ExistingFileWithUniqueId) — only the
        // success response was lost to a dropped connection. For a
        // payload-carrying request we can neither skip nor replay the update:
        // re-sending the pre-encrypted bytes reuses the original payload IV, and
        // the server rejects an unchanged IV with MustRotateKeyHeaderIvWhenUpdating
        // ("When updating a file, you must change the Iv") on every retry —
        // a deterministic ~48h loop (see the homebase.log image-to-Leela stall).
        // The bytes on disk are sealed under that IV so we can't safely re-key
        // them in memory. But we don't need to: the server already holds our
        // file and payloads under our own key. Mark the row complete (Sent).
        if (serverFileIsOurLandedCreate(original, serverFile)) {
            Logger.i(
                "$TAG retryAsUpdate: server already holds our create for uniqueId=$uniqueId " +
                        "(AES key matches, payload(s) present) — treating as Sent (lost-ack create), " +
                        "skipping the payload-IV-reuse update that the server would reject"
            )
            return
        }

        // The server rejects an update whose AES key differs from the existing
        // file's ("AES key must match"). When the client's key has diverged
        // (local DB lost the key and minted a fresh one, or a replaceEnqueue
        // superseded an in-flight create carrying a different key), re-encrypt
        // a header-only request with the SERVER's key — the owner can always
        // read it off the fetched header. Payload-carrying requests can't be
        // re-keyed (bytes are pre-encrypted on disk); they fall through to the
        // standard path and, on divergence, the server rejection drops the row
        // (see OutboxFailureClassifier).
        val rekeyed = rekeyedUpdateForExistingServerFile(
            original = original,
            serverKeyHeader = serverFile.keyHeader,
            serverVersionTag = versionTag,
        )
        if (rekeyed != null) {
            Logger.w(
                "$TAG retryAsUpdate: local AES key diverged from the server file for uniqueId=$uniqueId — " +
                        "re-encrypted the header with the server's key"
            )
        }

        // Standard path: original.metadata is already encrypted (encryptContent
        // was called before the request was serialized into the outbox). Just
        // stamp the versionTag.
        val updateRequest = rekeyed ?: UpdateFileByUniqueIdRequest(
            driveId = original.driveId,
            uniqueId = uniqueId,
            keyHeader = original.keyHeader,
            instructions = FileUpdateInstructionSet(
                transferIv = ByteArrayUtil.getRndByteArray(16),
                locale = UpdateLocale.Local,
                recipients = original.transitOptions?.recipients ?: emptyList(),
                manifest = UpdateManifest.build(
                    payloads = original.payloads,
                    toDeletePayloads = null,
                    thumbnails = original.thumbnails,
                    generatePayloadIv = false
                )
            ),
            metadata = original.metadata.copy(versionTag = versionTag),
            payloads = original.payloads,
            thumbnails = original.thumbnails
        )

        Logger.d("$TAG retryAsUpdate: sending update for uniqueId=$uniqueId versionTag=$versionTag")
        // Whole-percent throttle — see uploadNewFile above.
        val gate = WholePercentProgressGate()
        val updateResult = driveUploadProvider.updateFileByUniqueId(updateRequest, onProgress = { sent, total ->
            gate.admit(percentOf(sent, total))?.let { pct ->
                eventBus.emit(BackendEvent.OutboxEvent.ItemProgress(outboxRecord.driveId, outboxRecord.uniqueId, pct, sent))
            }
        })
        val rStatus = updateResult?.recipientStatus
        if (rStatus.isNullOrEmpty()) {
            Logger.i(
                "$TAG retryAsUpdate: SUCCESS — uniqueId=$uniqueId updated on server " +
                        "(was ExistingFileWithUniqueId) fileId=${updateResult?.fileId} (no recipients)"
            )
        } else {
            Logger.i(
                "$TAG retryAsUpdate: SUCCESS — uniqueId=$uniqueId updated on server " +
                        "(was ExistingFileWithUniqueId) fileId=${updateResult.fileId} " +
                        "recipientStatus=${rStatus.entries.joinToString { "${it.key}=${it.value}" }}"
            )
        }
    }
    // endregion

    private suspend fun updateFile(outboxRecord: Outbox, eventBus: EventBus) {
        val request = OdinSystemSerializer.deserialize<UpdateFileByUniqueIdRequest>(outboxRecord.json.decodeToString())
        // Pre-flight: same gate as uploadNewFile — see UploadValidation.kt.
        request.validateForUpload()
        // Whole-percent throttle — see uploadNewFile above.
        val gate = WholePercentProgressGate()
        val result = driveUploadProvider.updateFileByUniqueId(request, onProgress = { sent, total ->
            gate.admit(percentOf(sent, total))?.let { pct ->
                eventBus.emit(BackendEvent.OutboxEvent.ItemProgress(outboxRecord.driveId, outboxRecord.uniqueId, pct, sent))
            }
        })
        val rStatus = result?.recipientStatus
        if (rStatus.isNullOrEmpty()) {
            Logger.d(
                "$TAG updateFile: success uniqueId=${request.uniqueId} " +
                        "fileId=${result?.fileId} (no recipients)"
            )
        } else {
            Logger.i(
                "$TAG updateFile: success uniqueId=${request.uniqueId} " +
                        "fileId=${result.fileId} " +
                        "recipientStatus=${rStatus.entries.joinToString { "${it.key}=${it.value}" }}"
            )
        }
    }

    private suspend fun deleteFile(outboxRecord: Outbox) {
        val request = OdinSystemSerializer.deserialize<DeleteLocalFilesByFileIdRequest>(outboxRecord.json.decodeToString())
        if (request.hardDelete) {
            request.fileIds.forEach { fileId ->
                fileProvider.hardDeleteFile(request.driveId, fileId, request.recipients)
            }
        } else {
            fileProvider.deleteFiles(request.driveId, request.fileIds, request.recipients)
        }
    }

    private suspend fun updateLocalMetadataTags(outboxRecord: Outbox) {
        val request = OdinSystemSerializer.deserialize<UpdateLocalMetadataTagsOutboxRequest>(outboxRecord.json.decodeToString())
        val driveId = request.file.targetDrive.alias
        Logger.d(tag = "MarkAsRead") {
            "DriveOutboxUploader.updateLocalMetadataTags: outboxRow=${outboxRecord.uniqueId} drive=$driveId fileId=${request.file.fileId} uniqueId=${request.uniqueId} hasRequestVersionTag=${request.versionTag != null}"
        }
        // Resolve the CURRENT file at send time, not the fileId captured at enqueue.
        // For an own-send, that enqueue-time fileId is a temp id that the create
        // rekeys to the server id (rekeyCacheAfterCreate); the dependency chain
        // guarantees the create ran first, so the local row now holds the server
        // fileId + a fresh localAppData.versionTag. Resolving by the stable uniqueId
        // survives that rekey (mirrors rekeyCacheAfterCreate); older rows without a
        // uniqueId fall back to the fileId. Either way a genuinely missing file drops
        // the row (mirrors updateLocalMetadataContent). A null versionTag is fine — it
        // is the FIRST localAppData write on the file and the server treats it as a
        // create; the old "null versionTag ⇒ NotFound" guard wrongly conflated a
        // missing versionTag with a missing file.
        val header = if (request.uniqueId != null) {
            val identityId = credentialsManager.requireActiveCredentials().getIdentityId()
            databaseManager.driveMainIndex.selectHomebaseFileByUnique(identityId, driveId, request.uniqueId)
        } else {
            fileProvider.getFileHeader(driveId, Uuid.parse(request.file.fileId))
        }
        if (header == null) {
            Logger.w(tag = "MarkAsRead") {
                "DriveOutboxUploader.updateLocalMetadataTags: dropping outboxRow=${outboxRecord.uniqueId} drive=$driveId fileId=${request.file.fileId} uniqueId=${request.uniqueId} — local file no longer present"
            }
            throw NotFoundException()
        }
        val versionTag = request.versionTag ?: header.fileMetadata.localAppData?.versionTag?.toString()
        driveUploadProvider.uploadLocalMetadataTags(
            file = request.file.copy(fileId = header.fileId.toString()),
            localAppData = LocalAppData(versionTag = versionTag, tags = request.tags)
        )
    }

    private suspend fun updateLocalMetadataContent(outboxRecord: Outbox) {
        val request = OdinSystemSerializer.deserialize<UpdateLocalAppdataContentOutboxRequest>(outboxRecord.json.decodeToString())
        Logger.d(tag = "MarkAsRead") {
            "DriveOutboxUploader.updateLocalMetadataContent: outboxRow=${outboxRecord.uniqueId} drive=${request.driveId} fileId=${request.fileId} hasIv=${request.iv != null}"
        }
        val file = fileProvider.getFileHeader(request.driveId, request.fileId)
            ?: run {
                Logger.w(tag = "MarkAsRead") {
                    "DriveOutboxUploader.updateLocalMetadataContent: dropping outboxRow=${outboxRecord.uniqueId} drive=${request.driveId} fileId=${request.fileId} — local file no longer present"
                }
                throw NotFoundException()
            }
        val versionTag = request.versionTag
            ?: file.fileMetadata.localAppData?.versionTag?.toString()
        try {
            driveUploadProvider.uploadLocalMetadataContent(
                driveId = request.driveId,
                file = file,
                localAppData = LocalAppData(versionTag = versionTag, content = request.content, iv = request.iv)
            )
            Logger.d(tag = "MarkAsRead") {
                "DriveOutboxUploader.updateLocalMetadataContent: server accepted — outboxRow=${outboxRecord.uniqueId} fileId=${request.fileId} versionTag=$versionTag"
            }
        } catch (t: Throwable) {
            Logger.e(throwable = t, tag = "MarkAsRead") {
                "DriveOutboxUploader.updateLocalMetadataContent: FAILED outboxRow=${outboxRecord.uniqueId} drive=${request.driveId} fileId=${request.fileId}"
            }
            throw t
        }
    }

    private suspend fun sendReadReceiptByFileIds(outboxRecord: Outbox) {
        val request = OdinSystemSerializer.deserialize<SendReadReceiptByFileIdsOutboxRequest>(outboxRecord.json.decodeToString())
        Logger.d(tag = "MarkAsRead") {
            "DriveOutboxUploader.sendReadReceiptByFileIds: outboxRow=${outboxRecord.uniqueId} drive=${request.driveId} fileIdsCount=${request.fileIds.size}"
        }
        try {
            operationsProvider.sendReadReceiptBatch(
                driveId = request.driveId,
                fileIds = request.fileIds,
            )
            Logger.d(tag = "MarkAsRead") {
                "DriveOutboxUploader.sendReadReceiptByFileIds: server accepted — outboxRow=${outboxRecord.uniqueId}"
            }
        } catch (t: Throwable) {
            Logger.e(throwable = t, tag = "MarkAsRead") {
                "DriveOutboxUploader.sendReadReceiptByFileIds: FAILED outboxRow=${outboxRecord.uniqueId} drive=${request.driveId}"
            }
            throw t
        }
    }

    private suspend fun toggleReaction(outboxRecord: Outbox) {
        val request = OdinSystemSerializer.deserialize<ToggleReactionOutboxRequest>(outboxRecord.json.decodeToString())
        reactionProvider.toggleReaction(
            driveId = request.driveId,
            fileId = request.fileId,
            reaction = request.reaction,
            recipients = request.recipients,
        )
    }

    private suspend fun deleteFilesByGroupId(outboxRecord: Outbox) {
        val request = OdinSystemSerializer.deserialize<DeleteFilesByGroupIdOutboxRequest>(outboxRecord.json.decodeToString())
        fileProvider.deleteFilesByGroupId(request.driveId, request.groupIds)
    }

    private fun percentOf(sent: Long, total: Long?) =
        if (total != null && total > 0) (sent.toFloat() / total.toFloat()) * 100f else 0f

    companion object {
        private const val TAG = "DriveOutboxUploader"
        const val UploadNewFile = 1L
        const val UpdateFile = 2L
        const val DeleteFile = 3L
        const val UpdateLocalMetadataTags = 4L
        const val UpdateLocalMetadataContent = 5L
        const val SendReadReceiptByFileIds = 7L
        const val ToggleReaction = 8L
        const val DeleteFilesByGroupId = 9L
    }
}

/**
 * Root-cause fix for the "AES key must match" drop (see
 * OutboxFailureClassifier): when `retryAsUpdate` converts a failed
 * UploadNewFile into an update and the client's AES key has diverged from the
 * server file's, re-encrypt the request with the SERVER's key (fresh IV) so
 * the update is acceptable instead of deterministically rejected.
 *
 * Returns null when re-keying doesn't apply and the standard versionTag-stamp
 * path is correct or the only option:
 *  - the request isn't encrypted, or the server header has no usable key;
 *  - the keys already match (the common case — e.g. chat's edit-coalesce
 *    reuses the pending create's key);
 *  - the request carries payloads or thumbnails: their bytes are
 *    pre-encrypted on disk with the client key, and re-encrypting
 *    arbitrarily large media in memory is not safe. Those requests take the
 *    standard path; on divergence the server rejects and the row drops with
 *    the classifier's reason — the owning flows self-heal (location
 *    re-flushes its buffer, chat offers Retry).
 *
 * Pure given its inputs (plus IV randomness) — unit-tested in
 * RetryAsUpdateRekeyTest.
 */
@OptIn(ExperimentalEncodingApi::class)
internal suspend fun rekeyedUpdateForExistingServerFile(
    original: UploadFileRequest,
    serverKeyHeader: KeyHeader,
    serverVersionTag: Uuid?,
): UpdateFileByUniqueIdRequest? {
    if (!original.metadata.isEncrypted) return null
    if (serverKeyHeader == KeyHeader.empty()) return null
    if (original.payloads.isNotEmpty() || original.thumbnails.isNotEmpty()) return null
    if (original.keyHeader.aesKey == serverKeyHeader.aesKey) return null
    val uniqueId = original.metadata.appData.uniqueId ?: return null

    val newKeyHeader = KeyHeader(
        iv = ByteArrayUtil.getRndByteArray(16),
        aesKey = serverKeyHeader.aesKey,
    )

    // encryptContent stored Base64(AES(plaintext, clientKey)) — recover the
    // plaintext with the client key, re-encrypt with the server key.
    val reEncryptedContent = original.metadata.appData.content?.let { contentB64 ->
        val plaintext = original.keyHeader.decrypt(Base64.decode(contentB64))
        Base64.encode(newKeyHeader.encryptDataAes(plaintext))
    }

    return UpdateFileByUniqueIdRequest(
        driveId = original.driveId,
        uniqueId = uniqueId,
        keyHeader = newKeyHeader,
        instructions = FileUpdateInstructionSet(
            transferIv = ByteArrayUtil.getRndByteArray(16),
            locale = UpdateLocale.Local,
            recipients = original.transitOptions?.recipients ?: emptyList(),
            manifest = UpdateManifest.build(
                payloads = emptyList(),
                toDeletePayloads = null,
                thumbnails = emptyList(),
                generatePayloadIv = false,
            ),
        ),
        metadata = original.metadata.copy(
            versionTag = serverVersionTag,
            appData = original.metadata.appData.copy(content = reEncryptedContent),
        ),
        payloads = emptyList(),
        thumbnails = emptyList(),
    )
}

/**
 * Prepare a local file's payload descriptors for a cache re-key by stamping
 * each with its upload receipt's server-assigned `uid`/`lastModified` (the
 * thumbnail cache key's version segment). A descriptor with no matching
 * receipt — an old server that doesn't return receipts — keeps its payload
 * entry movable but has its thumbnail list stripped: without the server
 * lastModified its thumb target keys can't be computed, so those seeds are
 * left to age out via LRU rather than copied to dead keys.
 */
internal fun stampDescriptorsWithReceipts(
    localPayloads: List<PayloadDescriptor>,
    receipts: List<PayloadUploadReceipt>,
): List<PayloadDescriptor> {
    val receiptsByKey = receipts.associateBy { it.key.lowercase() }
    return localPayloads.map { descriptor ->
        val receipt = receiptsByKey[descriptor.key.lowercase()]
        if (receipt != null) {
            descriptor.copy(uid = receipt.uid, lastModified = receipt.lastModified)
        } else {
            descriptor.copy(thumbnails = null)
        }
    }
}

/**
 * True when the server file already carrying our uniqueId is OUR OWN
 * already-landed create whose success ack was lost (e.g. the connection
 * dropped right after the server created the file — the
 * `SocketException: Software caused connection abort` case). The signal:
 *
 *  - the request is payload-carrying (header-only divergence is handled by
 *    [rekeyedUpdateForExistingServerFile]; only payload requests hit the
 *    unrecoverable "must change the Iv" loop);
 *  - the server file's AES key equals ours — a random 256-bit key only matches
 *    if the server file is the one *we* created, not a stale/foreign file that
 *    happens to share the (also random) uniqueId;
 *  - every payload we were uploading is already present in the server header,
 *    so nothing is missing and there is genuinely nothing left to send.
 *
 * When this holds, [DriveOutboxUploader.retryAsUpdate] returns without issuing
 * the update PATCH — the row completes and the message shows Sent — instead of
 * replaying the original payload IV, which the server rejects forever.
 *
 * Pure given its inputs — unit-tested in RetryAsUpdateRecoverLostAckTest.
 */
internal fun serverFileIsOurLandedCreate(
    original: UploadFileRequest,
    serverFile: HomebaseFile,
): Boolean {
    if (original.payloads.isEmpty()) return false
    val serverKey = serverFile.keyHeader
    if (serverKey == KeyHeader.empty()) return false
    if (original.keyHeader.aesKey != serverKey.aesKey) return false
    val serverPayloadKeys = serverFile.fileMetadata.payloads?.mapTo(mutableSetOf()) { it.key } ?: emptySet()
    return original.payloads.all { it.key in serverPayloadKeys }
}