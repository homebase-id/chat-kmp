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
import id.homebase.api.client.drives.upload.UploadFileRequest
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.sync.database.DeviceBackupAsset
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
    /** The request [handOff] would queue, for a transport of the platform's own. */
    suspend fun request(spec: MediaUploadSpec): UploadFileRequest = uploadService.buildRequest(spec, scope)

    override suspend fun kick() {
        repeat(BULK_STREAMS) { outboxSync.send(force = true) }
    }

    private suspend fun handOffOriginal(
        row: DeviceBackupAsset,
        uniqueId: Uuid,
        mediaType: BackupMediaType,
        exported: ExportedAsset,
    ): HandOffResult {
        val spec = requests.build(row, uniqueId, mediaType, exported) ?: return HandOffResult.ChangedUnderUs
        return handled(spec, timed(BackupStage.Enqueue) { uploadService.upload(spec, scope) })
    }

    // The source is checked against its hash as it's read for its location, which an HLS stream has no place
    // for: by a read of its own for a transcode, and by the encrypt pass for an untouched original.
    private suspend fun handOffVideo(
        row: DeviceBackupAsset,
        uniqueId: Uuid,
        exported: ExportedAsset,
        expectedDigest: ByteArray,
    ): HandOffResult {
        val part = exported.parts.single()
        val options = requests.options()
        val reader = VideoLocationReader()
        if (!options.originalVideo && !hasher.matches(exported, part, expectedDigest) { reader.feed(it) }) return HandOffResult.ChangedUnderUs
        return try {
            upload(row, uniqueId, exported, expectedDigest, reader, options, untouched = false)
        } catch (e: VideoCompressionFailedException) {
            if (!options.originalVideo) throw e
            // A backup that isn't the original isn't a backup: keep the file even when no copy can be made to play.
            Logger.w(tag = TAG, throwable = e) { "no playable copy for $uniqueId; backing up the original alone" }
            upload(row, uniqueId, exported, expectedDigest, VideoLocationReader(), options.copy(originalVideo = false), untouched = true)
        }
    }

    private suspend fun upload(
        row: DeviceBackupAsset,
        uniqueId: Uuid,
        exported: ExportedAsset,
        expectedDigest: ByteArray,
        reader: VideoLocationReader,
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
            built.withOriginalEncrypted(part.path, keyHeader, expectedDigest, reader) ?: return HandOffResult.ChangedUnderUs
        } else {
            built
        }
        val location = reader.location
        val content = PhotoContent(latitude = location?.latitude, longitude = location?.longitude, altitude = location?.altitude)
        val spec = photoSpec(photosDriveId, uniqueId, keyHeader, bundle, photoMetadata(uniqueId, PhotobaseProtocol.VideoDataType, row.createdAt, content))
        return handled(spec, uploadService.upload(spec, scope))
    }

    private suspend fun handled(spec: MediaUploadSpec, outcome: UploadOutcome): HandOffResult {
        // What was encrypted here is ours until the Outbox takes it.
        if (outcome !is UploadOutcome.Enqueued) fileOps.deleteEncrypted(spec.bundle?.payloads)
        return when (outcome) {
            is UploadOutcome.Enqueued, is UploadOutcome.AlreadyQueued -> HandOffResult.Queued
            is UploadOutcome.SourceMissing -> throw AssetPermanentlyUnavailableException("source gone: ${outcome.missingPaths.joinToString()}")
            UploadOutcome.WouldStrandCreate -> throw BackupEnqueueFailedException("refused by the Outbox")
            is UploadOutcome.Failed -> throw BackupEnqueueFailedException("enqueue failed: ${outcome.cause.message}", outcome.cause)
        }
    }

    // The untouched original, encrypted here so its hash is checked on the same read; null when it changed.
    private suspend fun PayloadBundle.withOriginalEncrypted(path: String, keyHeader: KeyHeader, expectedDigest: ByteArray, reader: VideoLocationReader): PayloadBundle? {
        val encryptedPath = fileOps.createOutboxStagingPath("photo", ".encrypted")
        val hashed = fileOps.encryptWithPayloadHash(path, encryptedPath, keyHeader.aesKey, PayloadHashAlgorithm.Blake3) { chunk, offset, length ->
            reader.feed(chunk, offset, length)
        }
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
