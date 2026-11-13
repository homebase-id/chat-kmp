package id.homebase.core.feed.services

import co.touchlab.kermit.Logger
import id.homebase.api.client.drives.FileSystemType
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.client.drives.files.ThumbnailDescriptor
import id.homebase.api.client.drives.upload.UploadFileMetadata
import id.homebase.api.client.drives.upload.UploadFileRequest
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.serialization.Base64Bytes
import id.homebase.api.sync.database.OutboxSync
import id.homebase.api.sync.database.enqueued
import id.homebase.chat.services.outbox.OptimisticWriter
import id.homebase.upload.withMetadataStripped
import kotlin.uuid.Uuid

// Public feed files ship plaintext payloads, so the request bypasses UploadService's encryptBundle;
// their images are stripped here with the function the encryption step uses.
internal suspend fun enqueuePlaintextUpload(
    outboxSync: OutboxSync,
    optimisticWriter: OptimisticWriter,
    fileOps: FileOperationsProvider,
    unstrippedRequest: UploadFileRequest,
    tag: String,
    optimisticFileId: Uuid? = null,
    optimisticMetadata: UploadFileMetadata = unstrippedRequest.metadata,
    overPeer: Boolean = false,
) {
    val request = unstrippedRequest.copy(payloads = unstrippedRequest.payloads.map { withMetadataStripped(it, fileOps) })
    val enqueued = outboxSync.tryEnqueue(request, overPeer = overPeer)
    if (!enqueued.enqueued) error("Failed to enqueue upload (outbox: $enqueued)")

    val payloadDescriptors = request.payloads.map { payload ->
        PayloadDescriptor(
            key = payload.key,
            contentType = payload.contentType.ifEmpty { null },
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

    try {
        optimisticWriter.writeNewFile(
            driveId = request.driveId,
            keyHeader = request.keyHeader,
            unecryptedMetadata = optimisticMetadata,
            originalRecipientCount = request.transitOptions?.recipients?.size ?: 0,
            fileSystemType = FileSystemType.Standard,
            payloadDescriptors = payloadDescriptors,
            fileId = optimisticFileId ?: Uuid.random(),
        )
    } catch (e: Exception) {
        Logger.e(throwable = e, tag = tag) {
            "optimistic write failed (non-fatal) file=${request.metadata.appData.uniqueId}"
        }
    }
}
