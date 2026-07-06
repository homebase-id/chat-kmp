package id.homebase.chat.services.outbox

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.FileSystemType
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.client.drives.upload.UploadFileMetadata
import id.homebase.upload.OptimisticLocalWriter
import kotlin.uuid.Uuid

/**
 * Adapts chat's [OptimisticWriter] onto the upload pipeline's [OptimisticLocalWriter] port so
 * the shared `UploadService` (homebase-upload) can write optimistic rows without homebase-upload
 * depending on the chat module. `OptimisticWriter`'s richer conversation/reaction/placeholder
 * methods stay in chat and are not part of the port.
 */
class OptimisticWriterPort(private val writer: OptimisticWriter) : OptimisticLocalWriter {
    override suspend fun writeNewFile(
        driveId: Uuid,
        keyHeader: KeyHeader,
        unecryptedMetadata: UploadFileMetadata,
        originalRecipientCount: Int,
        fileSystemType: FileSystemType,
        payloadDescriptors: List<PayloadDescriptor>?,
        fileId: Uuid,
    ): Uuid = writer.writeNewFile(
        driveId, keyHeader, unecryptedMetadata, originalRecipientCount,
        fileSystemType, payloadDescriptors, fileId,
    )

    override suspend fun writeUpdate(
        driveId: Uuid,
        keyHeader: KeyHeader,
        unecryptedMetadata: UploadFileMetadata,
        payloadDescriptors: List<PayloadDescriptor>?,
    ) = writer.writeUpdate(driveId, keyHeader, unecryptedMetadata, payloadDescriptors)

    override suspend fun removeOptimisticFile(driveId: Uuid, uniqueId: Uuid): Boolean =
        writer.removeOptimisticFile(driveId, uniqueId)
}
