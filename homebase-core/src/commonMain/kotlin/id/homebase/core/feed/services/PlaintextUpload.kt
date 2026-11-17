package id.homebase.core.feed.services

import co.touchlab.kermit.Logger
import id.homebase.api.client.drives.FileSystemType
import id.homebase.api.client.drives.upload.UploadFileMetadata
import id.homebase.api.client.drives.upload.UploadFileRequest
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.sync.database.OutboxSync
import id.homebase.api.sync.database.enqueued
import id.homebase.chat.services.outbox.OptimisticWriter
import id.homebase.upload.PayloadBundle
import id.homebase.upload.PayloadCacheSeeder
import id.homebase.upload.toPayloadDescriptors
import id.homebase.upload.withMetadataStripped
import kotlin.uuid.Uuid

// Public feed files ship plaintext payloads, so the request bypasses UploadService's encryptBundle.
internal suspend fun enqueuePlaintextUpload(
    outboxSync: OutboxSync,
    optimisticWriter: OptimisticWriter,
    payloadCacheSeeder: PayloadCacheSeeder,
    fileOps: FileOperationsProvider,
    request: UploadFileRequest,
    tag: String,
    optimisticFileId: Uuid? = null,
    optimisticMetadata: UploadFileMetadata = request.metadata,
    overPeer: Boolean = false,
) {
    // Staged copies belong to the outbox row once it exists; until then they are ours to delete.
    val staged = mutableListOf<String>()
    val payloads = try {
        request.payloads.map { payload ->
            withMetadataStripped(payload, fileOps, staged = true).also { if (it.filePath != payload.filePath) staged += it.filePath }
        }.also { stripped ->
            val enqueued = outboxSync.tryEnqueue(request.copy(payloads = stripped), overPeer = overPeer)
            if (!enqueued.enqueued) error("Failed to enqueue upload (outbox: $enqueued)")
        }
    } catch (e: Throwable) {
        staged.forEach { runCatching { fileOps.deleteTempFile(it) } }
        throw e
    }

    val sent = PayloadBundle(payloads, request.thumbnails, previewThumbs = emptyList())
    val fileId = optimisticFileId ?: Uuid.random()
    try {
        // Seeded before the row is written, which makes the views read it; over peer the file lives on the peer's drive.
        if (!overPeer) payloadCacheSeeder.seed(request.driveId, fileId, sent, encrypted = false)
        optimisticWriter.writeNewFile(
            driveId = request.driveId,
            keyHeader = request.keyHeader,
            unecryptedMetadata = optimisticMetadata,
            originalRecipientCount = request.transitOptions?.recipients?.size ?: 0,
            fileSystemType = FileSystemType.Standard,
            payloadDescriptors = sent.toPayloadDescriptors(),
            fileId = fileId,
        )
    } catch (e: Exception) {
        Logger.e(throwable = e, tag = tag) {
            "optimistic seed/write failed (non-fatal) file=${request.metadata.appData.uniqueId}"
        }
    }
}
