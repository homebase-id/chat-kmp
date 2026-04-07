package id.homebase.api.client.drives.files

import co.touchlab.kermit.Logger
import id.homebase.api.client.ClientException
import id.homebase.api.client.drives.upload.DriveUploadProvider
import id.homebase.api.client.drives.upload.LocalAppData
import id.homebase.api.client.drives.upload.UpdateFileByUniqueIdRequest
import id.homebase.api.client.drives.upload.UpdateLocalMetadataContentOutboxRequest
import id.homebase.api.client.drives.upload.UpdateLocalMetadataTagsOutboxRequest
import id.homebase.api.client.drives.upload.UploadFileRequest
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
                Logger.w("Dropping outbox item ${outboxRecord.uniqueId} uploadType=${outboxRecord.uploadType} — 400 Bad Request: ${e.message}")
                return
            }
            throw e
        }
    }

    private suspend fun uploadNewFile(outboxRecord: Outbox, eventBus: EventBus) {
        val request = OdinSystemSerializer.deserialize<UploadFileRequest>(outboxRecord.json.decodeToString())
        driveUploadProvider.uploadFile(request, onProgress = { sent, total ->
//            println("Upload: Sent $sent | Total: $total")
            val percent = percentOf(sent, total)
            eventBus.emit(BackendEvent.OutboxEvent.ItemProgress(outboxRecord.driveId, outboxRecord.uniqueId, percent, sent))
//            println("Upload: $percent%")
        })
    }

    private suspend fun updateFile(outboxRecord: Outbox, eventBus: EventBus) {
        val request = OdinSystemSerializer.deserialize<UpdateFileByUniqueIdRequest>(outboxRecord.json.decodeToString())
        driveUploadProvider.updateFileByUniqueId(request, onProgress = { sent, total ->
            val percent = percentOf(sent, total)
            eventBus.emit(BackendEvent.OutboxEvent.ItemProgress(outboxRecord.driveId, outboxRecord.uniqueId, percent, sent))
//            println("Upload: $percent%")
        })
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