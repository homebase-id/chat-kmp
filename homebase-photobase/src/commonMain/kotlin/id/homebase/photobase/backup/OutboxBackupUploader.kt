package id.homebase.photobase.backup

import id.homebase.api.client.drives.upload.EmbeddedThumb
import id.homebase.api.client.KeyHeader
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.sync.database.DeviceBackupAsset
import id.homebase.api.sync.database.EnqueueResult
import id.homebase.api.sync.database.OUTBOX_BULK_PRIORITY
import id.homebase.api.sync.database.OutboxSync
import id.homebase.core.photobase.PhotobaseProtocol
import id.homebase.upload.MediaUploadSpec
import id.homebase.upload.UploadOutcome
import id.homebase.upload.UploadService
import id.homebase.upload.UploadSource
import id.homebase.upload.UserUploadBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.uuid.Uuid

class BackupEnqueueFailedException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** One Homebase file through the shared Outbox: no optimistic row, 4105 counts as sent. */
class OutboxBackupUploader(
    private val fileOps: FileOperationsProvider,
    private val source: DeviceChangeSource,
    private val hasher: AssetHasher,
    private val requests: BackupRequestBuilder,
    private val outboxSync: OutboxSync,
    private val uploadService: UploadService,
    private val scope: CoroutineScope,
    private val photosDriveId: Uuid = PhotobaseProtocol.DriveAlias,
) : BackupUploader {

    private val videoProcessing = Mutex()

    override suspend fun handOff(row: DeviceBackupAsset, uniqueId: Uuid): HandOffResult {
        val mediaType = BackupMediaType.ofCode(row.mediaType)
        val exported = source.export(row.deviceAssetId, mediaType)
        try {
            return if (mediaType == BackupMediaType.Video) {
                videoProcessing.withLock { handOffVideo(row, uniqueId, exported, digestsOf(row).single()) }
            } else {
                handOffOriginal(row, uniqueId, mediaType, exported)
            }
        } finally {
            exported.deleteTemporaryParts(fileOps)
        }
    }

    override suspend fun stillSending(rows: Map<DeviceBackupAsset, Uuid>): Set<Uuid> =
        rows.values.filterTo(mutableSetOf()) { outboxSync.pendingUploadType(photosDriveId, it) != null }

    // A plain send() is skipped in a background wake, where the WebSocket never comes up. Two
    // workers, because a forced drain's own re-entry is a plain send() too.
    override suspend fun kick() {
        repeat(BULK_STREAMS) { outboxSync.send(force = true) }
    }

    private suspend fun handOffOriginal(
        row: DeviceBackupAsset,
        uniqueId: Uuid,
        mediaType: BackupMediaType,
        exported: ExportedAsset,
    ): HandOffResult {
        val request = requests.build(row, uniqueId, mediaType, exported) ?: return HandOffResult.ChangedUnderUs
        var queued = false
        try {
            when (val enqueue = timed(BackupStage.Enqueue) { outboxSync.tryEnqueue(request, priority = OUTBOX_BULK_PRIORITY, sendNow = false) }) {
                EnqueueResult.Enqueued -> queued = true
                EnqueueResult.AlreadyQueued -> Unit
                EnqueueResult.WouldStrandCreate -> throw BackupEnqueueFailedException("refused by the Outbox")
                is EnqueueResult.Failed -> throw BackupEnqueueFailedException("enqueue failed: ${enqueue.cause.message}", enqueue.cause)
            }
            return HandOffResult.Queued
        } finally {
            if (!queued) request.payloads.forEach { runCatching { fileOps.deleteTempFile(it.filePath) } }
        }
    }

    // HLS re-encodes, so the server's hash can't vouch for the original: check the bytes about to be encoded instead.
    private suspend fun handOffVideo(
        row: DeviceBackupAsset,
        uniqueId: Uuid,
        exported: ExportedAsset,
        expectedDigest: ByteArray,
    ): HandOffResult {
        val part = exported.parts.single()
        if (!exported.isHashedCopy && !hasher.digest(part.path).contentEquals(expectedDigest)) return HandOffResult.ChangedUnderUs
        // Inside the encrypted content, because omitPlaintextPreviews keeps the tiny out of the plaintext field.
        fun videoMetadata(tiny: EmbeddedThumb?) =
            photoMetadata(uniqueId, PhotobaseProtocol.VideoDataType, row.createdAt, PhotoContent().withTiny(tiny))
        val spec = MediaUploadSpec(
            driveId = photosDriveId,
            uniqueId = uniqueId,
            keyHeader = KeyHeader.newRandom16(),
            bundle = UserUploadBuilder.build(
                listOf(UploadSource(part.path, part.contentType, pickedName = exported.fileName)),
                PHOTOS_UPLOAD_OPTIONS,
                fileOps,
            ) { PhotobaseProtocol.VideoPayloadKey },
            metadata = videoMetadata(null),
            metadataFromBundle = { bundle -> videoMetadata(bundle.payloads.firstNotNullOfOrNull { it.previewThumbnail }) },
            priority = OUTBOX_BULK_PRIORITY,
            sendNow = false,
            writeOptimistic = false,
            seedCache = false,
            omitPlaintextPreviews = true,
            existingFileIsSuccess = true,
        )
        return when (val outcome = uploadService.upload(spec, scope)) {
            is UploadOutcome.Enqueued, is UploadOutcome.AlreadyQueued -> HandOffResult.Queued
            is UploadOutcome.SourceMissing -> throw AssetPermanentlyUnavailableException("source gone: ${outcome.missingPaths.joinToString()}")
            UploadOutcome.WouldStrandCreate -> throw BackupEnqueueFailedException("refused by the Outbox")
            is UploadOutcome.Failed -> throw BackupEnqueueFailedException("enqueue failed: ${outcome.cause.message}", outcome.cause)
        }
    }

    private companion object {
        const val BULK_STREAMS = 2
    }
}
