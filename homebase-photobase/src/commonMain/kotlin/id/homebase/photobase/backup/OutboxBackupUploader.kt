package id.homebase.photobase.backup

import id.homebase.upload.UploadOptions
import id.homebase.upload.PayloadBundle
import id.homebase.api.video.VideoLocationReader
import id.homebase.api.video.VideoCompressionFailedException
import id.homebase.api.crypto.plaintextContentHash
import id.homebase.api.crypto.encryptWithPayloadHash
import id.homebase.api.client.drives.files.PayloadHashAlgorithm
import co.touchlab.kermit.Logger
import id.homebase.api.client.KeyHeader
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.sync.database.DeviceBackupAsset
import id.homebase.api.sync.database.EnqueueResult
import id.homebase.api.sync.database.OUTBOX_BULK_PRIORITY
import id.homebase.api.sync.database.OutboxSync
import id.homebase.core.photobase.PhotobaseProtocol
import id.homebase.upload.LibraryKind
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

    // A transcode's bytes aren't the library's file, so the source is checked as it's read for its location,
    // which an HLS stream has no place for. An untouched original is checked again as it's encrypted.
    private suspend fun handOffVideo(
        row: DeviceBackupAsset,
        uniqueId: Uuid,
        exported: ExportedAsset,
        expectedDigest: ByteArray,
    ): HandOffResult {
        val part = exported.parts.single()
        val reader = VideoLocationReader()
        if (!hasher.matches(exported, part, expectedDigest, reader::feed)) return HandOffResult.ChangedUnderUs
        val location = reader.location
        val content = PhotoContent(latitude = location?.latitude, longitude = location?.longitude, altitude = location?.altitude)
        val options = requests.options()
        return try {
            upload(row, uniqueId, exported, expectedDigest, content, options, untouched = false)
        } catch (e: VideoCompressionFailedException) {
            if (!options.originalVideo) throw e
            // A backup that isn't the original isn't a backup: keep the file even when no copy can be made to play.
            Logger.w(tag = TAG, throwable = e) { "no playable copy for $uniqueId; backing up the original alone" }
            upload(row, uniqueId, exported, expectedDigest, content, options.copy(originalVideo = false), untouched = true)
        }
    }

    private suspend fun upload(
        row: DeviceBackupAsset,
        uniqueId: Uuid,
        exported: ExportedAsset,
        expectedDigest: ByteArray,
        content: PhotoContent,
        options: UploadOptions,
        untouched: Boolean,
    ): HandOffResult {
        val part = exported.parts.single()
        val keyHeader = KeyHeader.newRandom16()
        val built = UserUploadBuilder.build(
            listOf(UploadSource(part.path, part.contentType, pickedName = exported.fileName, libraryKind = LibraryKind.VIDEO, untouched = untouched)),
            options,
            fileOps,
        ) { PhotobaseProtocol.VideoPayloadKey }
        val bundle = if (untouched || options.originalVideo) {
            built.withOriginalEncrypted(part.path, keyHeader, expectedDigest) ?: return HandOffResult.ChangedUnderUs
        } else {
            built
        }
        val spec = MediaUploadSpec(
            driveId = photosDriveId,
            uniqueId = uniqueId,
            keyHeader = keyHeader,
            bundle = bundle,
            metadata = photoMetadata(uniqueId, PhotobaseProtocol.VideoDataType, row.createdAt, content),
            priority = OUTBOX_BULK_PRIORITY,
            sendNow = false,
            writeOptimistic = false,
            seedCache = false,
            existingFileIsSuccess = true,
            sealDescriptorContent = true,
        )
        return when (val outcome = uploadService.upload(spec, scope)) {
            is UploadOutcome.Enqueued, is UploadOutcome.AlreadyQueued -> HandOffResult.Queued
            is UploadOutcome.SourceMissing -> throw AssetPermanentlyUnavailableException("source gone: ${outcome.missingPaths.joinToString()}")
            UploadOutcome.WouldStrandCreate -> throw BackupEnqueueFailedException("refused by the Outbox")
            is UploadOutcome.Failed -> throw BackupEnqueueFailedException("enqueue failed: ${outcome.cause.message}", outcome.cause)
        }
    }

    // The untouched original, encrypted here so its hash is checked on the same read; null when it changed.
    private suspend fun PayloadBundle.withOriginalEncrypted(path: String, keyHeader: KeyHeader, expectedDigest: ByteArray): PayloadBundle? {
        val encryptedPath = fileOps.createOutboxStagingPath("photo", ".encrypted")
        val hashed = fileOps.encryptWithPayloadHash(path, encryptedPath, keyHeader.aesKey, PayloadHashAlgorithm.Blake3)
        val digest = hashed.hash.plaintextContentHash(KeyHeader(hashed.iv, keyHeader.aesKey))
        if (digest == null || !digest.contentEquals(expectedDigest)) {
            runCatching { fileOps.deleteTempFile(encryptedPath) }
            return null
        }
        return copy(payloads = payloads.map {
            if (it.key != PhotobaseProtocol.VideoPayloadKey) it
            else it.copy(filePath = encryptedPath, iv = hashed.iv, hash = hashed.hash, isPreEncrypted = true)
        })
    }

    private companion object {
        const val BULK_STREAMS = 2
        const val TAG = "PhotoBackup"
    }
}
