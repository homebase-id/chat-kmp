package id.homebase.api.client.drives.files

import co.touchlab.kermit.Logger
import id.homebase.api.client.ClientException
import id.homebase.api.client.OdinClientErrorCode
import id.homebase.api.client.drives.upload.DriveUploadProvider
import id.homebase.api.client.drives.upload.FileUpdateInstructionSet
import id.homebase.api.client.drives.upload.LocalAppData
import id.homebase.api.client.drives.upload.UpdateFileByUniqueIdRequest
import id.homebase.api.client.drives.upload.UpdateLocalMetadataContentOutboxRequest
import id.homebase.api.client.drives.upload.UpdateLocalMetadataTagsOutboxRequest
import id.homebase.api.client.drives.upload.UpdateLocale
import id.homebase.api.client.drives.upload.UpdateManifest
import id.homebase.api.client.drives.upload.UploadFileRequest
import id.homebase.api.crypto.ByteArrayUtil
import id.homebase.api.client.drives.files.reactions.DriveFileGroupReactionProvider
import id.homebase.api.client.drives.files.reactions.ToggleReactionOutboxRequest
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.sync.database.Outbox
import id.homebase.api.sync.database.OutboxUploader
import kotlin.uuid.Uuid

class DriveOutboxUploader(
    private val driveUploadProvider: DriveUploadProvider,
    private val fileProvider: DriveFileProvider,
    private val operationsProvider: DriveFileOperationsProvider,
    private val reactionProvider: DriveFileGroupReactionProvider,
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
                SendReadReceiptByTime -> sendReadReceiptByTime(outboxRecord)
                ToggleReaction -> toggleReaction(outboxRecord)
                DeleteFilesByGroupId -> deleteFilesByGroupId(outboxRecord)
            }
        } catch (e: ClientException) {
            if (e.status == 400) {
                // Self-recipient: the outbox item's recipient list contains the
                // logged-in identity. The server will reject this forever, so drop
                // the row rather than scheduling 20 retries. Title-match because the
                // server returns errorCode=UnhandledScenario for this case; there's
                // no dedicated enum value. Mirrors the VersionTagMismatch pattern
                // below: return normally and OutboxSync deletes the row.
                if (e.message?.startsWith("Cannot transfer to yourself") == true) {
                    Logger.w(
                        "$TAG upload: dropping outbox item ${outboxRecord.uniqueId} " +
                                "uploadType=${outboxRecord.uploadType} — terminal: ${e.message}"
                    )
                    return
                }
                when (e.errorCode) {
                    OdinClientErrorCode.VersionTagMismatch -> {
                        Logger.w(
                            "Discarding outbox item ${outboxRecord.uniqueId} " +
                                    "uploadType=${outboxRecord.uploadType} — VersionTagMismatch: ${e.message}"
                        )
                        return
                    }

                    else -> {
                        Logger.e(
                            "$TAG upload: 400 for outbox item ${outboxRecord.uniqueId} " +
                                    "uploadType=${outboxRecord.uploadType} errorCode=${e.errorCode} " +
                                    "— will retry (server message: ${e.message})"
                        )
                        throw e
                    }
                }
            }
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
        Logger.d("$TAG uploadNewFile: uniqueId=${request.metadata.appData.uniqueId} fileType=${request.metadata.appData.fileType} driveId=${request.driveId}")
        try {
            val result = driveUploadProvider.uploadFile(request, onProgress = { sent, total ->
                val percent = percentOf(sent, total)
                eventBus.emit(BackendEvent.OutboxEvent.ItemProgress(outboxRecord.driveId, outboxRecord.uniqueId, percent, sent))
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

        // original.metadata is already encrypted (encryptContent was called before
        // the request was serialized into the outbox). Just stamp the versionTag.
        val metadataWithVersionTag = original.metadata.copy(
            versionTag = versionTag
        )

        val updateRequest = UpdateFileByUniqueIdRequest(
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
            metadata = metadataWithVersionTag,
            payloads = original.payloads,
            thumbnails = original.thumbnails
        )

        Logger.d("$TAG retryAsUpdate: sending update for uniqueId=$uniqueId versionTag=$versionTag")
        val updateResult = driveUploadProvider.updateFileByUniqueId(updateRequest, onProgress = { sent, total ->
            val percent = percentOf(sent, total)
            eventBus.emit(BackendEvent.OutboxEvent.ItemProgress(outboxRecord.driveId, outboxRecord.uniqueId, percent, sent))
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
        val result = driveUploadProvider.updateFileByUniqueId(request, onProgress = { sent, total ->
            val percent = percentOf(sent, total)
            eventBus.emit(BackendEvent.OutboxEvent.ItemProgress(outboxRecord.driveId, outboxRecord.uniqueId, percent, sent))
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
        val versionTag = request.versionTag
            ?: fileProvider.getFileHeader(request.file.targetDrive.alias, Uuid.parse(request.file.fileId))
                ?.fileMetadata?.localAppData?.versionTag?.toString()
        driveUploadProvider.uploadLocalMetadataTags(
            file = request.file,
            localAppData = LocalAppData(versionTag = versionTag, tags = request.tags)
        )
    }

    private suspend fun updateLocalMetadataContent(outboxRecord: Outbox) {
        val request = OdinSystemSerializer.deserialize<UpdateLocalMetadataContentOutboxRequest>(outboxRecord.json.decodeToString())
        val file = fileProvider.getFileHeader(request.driveId, request.fileId)
            ?: error("File not found for local metadata content update: ${request.fileId}")
        val versionTag = request.versionTag
            ?: file.fileMetadata.localAppData?.versionTag?.toString()
        driveUploadProvider.uploadLocalMetadataContent(
            driveId = request.driveId,
            file = file,
            localAppData = LocalAppData(versionTag = versionTag, content = request.content, iv = request.iv)
        )
    }

    private suspend fun sendReadReceiptByTime(outboxRecord: Outbox) {
        val request = OdinSystemSerializer.deserialize<SendReadReceiptByTimeOutboxRequest>(outboxRecord.json.decodeToString())
        operationsProvider.sendReadReceiptBatch(
            driveId = request.driveId,
            fileType = request.fileType,
            dataType = request.dataType,
            groupId = request.groupId,
            endTime = request.endTime,
        )
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
        const val SendReadReceiptByTime = 6L
        const val ToggleReaction = 8L
        const val DeleteFilesByGroupId = 9L
    }
}