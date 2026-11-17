package id.homebase.upload

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.FileSystemType
import id.homebase.api.client.drives.files.DriveFileProvider
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.client.drives.upload.UploadFileMetadata
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.sync.database.Outbox
import id.homebase.api.sync.database.OutboxUploader
import kotlin.test.assertTrue
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException

/** Records the optimistic writes the pipeline performs. */
internal class RecordingOptimisticWriter : OptimisticLocalWriter {
    val newFileIds = mutableListOf<Uuid>()
    var updateCount = 0
        private set

    /** The descriptors passed to the LAST `writeUpdate` — null means "preserve existing payloads". */
    var lastUpdateDescriptors: List<PayloadDescriptor>? = null
        private set

    override suspend fun writeNewFile(
        driveId: Uuid,
        keyHeader: KeyHeader,
        unecryptedMetadata: UploadFileMetadata,
        originalRecipientCount: Int,
        fileSystemType: FileSystemType,
        payloadDescriptors: List<PayloadDescriptor>?,
        fileId: Uuid,
    ): Uuid {
        newFileIds += fileId
        return fileId
    }

    override suspend fun writeUpdate(
        driveId: Uuid,
        keyHeader: KeyHeader,
        unecryptedMetadata: UploadFileMetadata,
        payloadDescriptors: List<PayloadDescriptor>?,
    ) {
        updateCount++
        lastUpdateDescriptors = payloadDescriptors
    }

    override suspend fun removeOptimisticFile(driveId: Uuid, uniqueId: Uuid): Boolean = true
}

/** Records the fileId of each seed instead of doing real byte IO; [cancelOnSeed] stops the upload there instead. */
internal class RecordingSeeder(
    driveFileProvider: DriveFileProvider,
    fileOperationsProvider: FileOperationsProvider,
    private val cancelOnSeed: Boolean = false,
) : PayloadCacheSeeder(driveFileProvider, fileOperationsProvider) {
    val seededFileIds = mutableListOf<Uuid>()
    val unseededFileIds = mutableListOf<Uuid>()
    val rowsAtSeed = mutableListOf<Long>()
    var countRows: suspend () -> Long = { 0 }

    override suspend fun seed(driveId: Uuid, fileId: Uuid, bundle: PayloadBundle, encrypted: Boolean) {
        if (cancelOnSeed) throw CancellationException("stopped while seeding")
        assertTrue(encrypted, "UploadService only ever seeds the ciphertext it made")
        rowsAtSeed += countRows()
        seededFileIds += fileId
    }

    override suspend fun unseed(driveId: Uuid, fileId: Uuid, bundle: PayloadBundle) {
        unseededFileIds += fileId
    }
}

internal object ThrowingUploader : OutboxUploader {
    override suspend fun upload(outboxRecord: Outbox, eventBus: EventBus) =
        error("OutboxUploader.upload must not run (setOnline(false))")
}
