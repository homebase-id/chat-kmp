package id.homebase.api.client.drives.files

import id.homebase.api.client.drives.upload.DriveUploadProvider
import id.homebase.api.client.drives.upload.UpdateFileByUniqueIdRequest
import id.homebase.api.client.drives.upload.UploadFileRequest
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
        val jsonString = outboxRecord.json.decodeToString()
        val request = OdinSystemSerializer.deserialize<UploadFileRequest>(jsonString)
        driveUploadProvider.uploadFile(request)
    }

    //TODO: Michael we need to do this too
    suspend fun updateFileByUniqueIdRequest(
        outboxRecord: Outbox,
        eventBus: EventBus
    ) {
        val jsonString = outboxRecord.json.decodeToString()
        val request = OdinSystemSerializer.deserialize<UpdateFileByUniqueIdRequest>(jsonString)
        driveUploadProvider.updateFileByUniqueId(request)
    }
}
