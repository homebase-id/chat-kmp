package id.homebase.api.client.drives.files

import id.homebase.api.client.drives.upload.DriveUploadProvider
import id.homebase.api.client.drives.upload.LocalAppData
import id.homebase.api.client.drives.upload.UpdateFileByUniqueIdRequest
import id.homebase.api.client.drives.upload.UpdateLocalMetadataContentOutboxRequest
import id.homebase.api.client.drives.upload.UpdateLocalMetadataTagsOutboxRequest
import id.homebase.api.client.drives.upload.UploadFileRequest
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.sync.database.Outbox
import id.homebase.api.sync.database.OutboxUploader

class DriveOutboxUploader(
    private val driveUploadProvider: DriveUploadProvider,
    private val fileProvider: DriveFileProvider
    ) : OutboxUploader {

    override suspend fun upload(
        outboxRecord: Outbox,
        eventBus: EventBus
    ) {
        if (outboxRecord.uploadType == UploadNewFile) {
            val jsonString = outboxRecord.json.decodeToString()
            val request = OdinSystemSerializer.deserialize<UploadFileRequest>(jsonString)

            driveUploadProvider.uploadFile(
                request,
                onProgress = { sent, total ->

                    println("Upload: Sent $sent | Total: $total")

                    val percent =
                        if (total != null && total > 0)
                            (sent.toFloat() / total.toFloat()) * 100f
                        else
                            0f

                    eventBus.emit(
                        BackendEvent.OutboxEvent.ItemProgress(
                            outboxRecord.driveId,
                            outboxRecord.uniqueId,
                            percent,
                            sent
                        )
                    )
                    println("Upload: $percent%")
                })

        } else if (outboxRecord.uploadType == UpdateFile) {
            val jsonString = outboxRecord.json.decodeToString()
            val request = OdinSystemSerializer.deserialize<UpdateFileByUniqueIdRequest>(jsonString)
            driveUploadProvider.updateFileByUniqueId(
                request,
                onProgress = { sent, total ->
                    val percent =
                        if (total != null && total > 0)
                            (sent.toFloat() / total.toFloat()) * 100f
                        else
                            0f

                    eventBus.emit(
                        BackendEvent.OutboxEvent.ItemProgress(
                            outboxRecord.driveId,
                            outboxRecord.uniqueId,
                            percent,
                            sent
                        )
                    )
                    println("Upload: $percent%")
                })
        } else if (outboxRecord.uploadType == DeleteFile) {
            val jsonString = outboxRecord.json.decodeToString()
            val request = OdinSystemSerializer.deserialize<DeleteLocalFilesByFileIdRequest>(jsonString)
            fileProvider.deleteFiles(request.driveId, request.fileIds)
        } else if (outboxRecord.uploadType == UpdateLocalMetadataTags) {
            val jsonString = outboxRecord.json.decodeToString()
            val request = OdinSystemSerializer.deserialize<UpdateLocalMetadataTagsOutboxRequest>(jsonString)
            driveUploadProvider.uploadLocalMetadataTags(
                file = request.file,
                localAppData = LocalAppData(versionTag = request.versionTag, tags = request.tags)
            )
        } else if (outboxRecord.uploadType == UpdateLocalMetadataContent) {
            val jsonString = outboxRecord.json.decodeToString()
            val request = OdinSystemSerializer.deserialize<UpdateLocalMetadataContentOutboxRequest>(jsonString)
            val file = fileProvider.getFileHeader(request.driveId, request.fileId)
                ?: error("File not found for local metadata content update: ${request.fileId}")
            driveUploadProvider.uploadLocalMetadataContent(
                driveId = request.driveId,
                file = file,
                localAppData = LocalAppData(
                    versionTag = request.versionTag,
                    content = request.content,
                    iv = request.iv
                )
            )
        }
    }

    companion object {
        const val UploadNewFile = 1L
        const val UpdateFile = 2L
        const val DeleteFile = 3L
        const val UpdateLocalMetadataTags = 4L
        const val UpdateLocalMetadataContent = 5L
    }
}
