package id.homebase.api.client.drives.files

import id.homebase.api.client.drives.upload.DriveUploadProvider
import id.homebase.api.client.drives.upload.UpdateFileByUniqueIdRequest
import id.homebase.api.client.drives.upload.UploadFileRequest
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.sync.database.Outbox
import id.homebase.api.sync.database.OutboxUploader

class DriveOutboxUploader(
    private val driveUploadProvider: DriveUploadProvider
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
        }
    }

    companion object {
        const val UploadNewFile = 1L
        const val UpdateFile = 2L
    }
}
