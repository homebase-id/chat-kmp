package id.homebase.upload

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.FileSystemType
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.client.drives.upload.UploadFileMetadata
import kotlin.uuid.Uuid

/**
 * The subset of optimistic local-DB writes the shared [UploadService] needs, declared as a
 * port so the upload pipeline (homebase-upload) doesn't depend on chat's `OptimisticWriter`.
 *
 * `OptimisticWriter` (in homebase-chat) is adapted onto this in DI; its richer conversation /
 * reaction / placeholder methods stay in chat. All parameters are homebase-api types, so the
 * port carries no feature coupling.
 */
interface OptimisticLocalWriter {
    /** Optimistically write a brand-new file so it appears immediately; returns its fileId. */
    suspend fun writeNewFile(
        driveId: Uuid,
        keyHeader: KeyHeader,
        unecryptedMetadata: UploadFileMetadata,
        originalRecipientCount: Int,
        fileSystemType: FileSystemType,
        payloadDescriptors: List<PayloadDescriptor>?,
        fileId: Uuid,
    ): Uuid

    /** Optimistically apply an update to an existing file. */
    suspend fun writeUpdate(
        driveId: Uuid,
        keyHeader: KeyHeader,
        unecryptedMetadata: UploadFileMetadata,
        payloadDescriptors: List<PayloadDescriptor>?,
    )

    /** Roll back an optimistic file (e.g. a pre-enqueue failure). */
    suspend fun removeOptimisticFile(driveId: Uuid, uniqueId: Uuid): Boolean
}
