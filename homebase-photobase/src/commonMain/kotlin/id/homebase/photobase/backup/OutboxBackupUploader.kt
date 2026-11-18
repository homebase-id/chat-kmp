package id.homebase.photobase.backup

import id.homebase.upload.UploadOptions
import id.homebase.api.video.VideoLocationReader
import id.homebase.api.video.VideoCompressionFailedException
import id.homebase.api.client.drives.files.PayloadHashAlgorithm
import co.touchlab.kermit.Logger
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.sync.database.DeviceBackupAsset
import id.homebase.api.sync.database.OutboxSync
import id.homebase.core.photobase.PhotobaseProtocol
import id.homebase.api.file.SourceUnavailableException
import id.homebase.upload.ContentChangedException
import id.homebase.upload.ContentHashes
import id.homebase.upload.LibraryKind
import id.homebase.upload.MediaUploadSpec
import id.homebase.upload.PreparedUpload
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

    /** What [handOff] would queue, for a transport of the platform's own; null when a file changed since it was hashed. */
    suspend fun prepare(spec: MediaUploadSpec): PreparedUpload? = try {
        timed(BackupStage.Encrypt) { uploadService.prepare(spec, scope) }
    } catch (_: ContentChangedException) {
        null
    } catch (e: SourceUnavailableException) {
        // Retried: the next attempt exports again, and the export says if the asset is gone.
        throw BackupEnqueueFailedException("a file to send is gone: ${e.path}", e)
    }

    private suspend fun send(spec: MediaUploadSpec): HandOffResult {
        val prepared = prepare(spec) ?: return HandOffResult.ChangedUnderUs
        return handedOff(timed(BackupStage.Enqueue) { uploadService.queue(prepared) })
    }

    private suspend fun handOffOriginal(
        row: DeviceBackupAsset,
        uniqueId: Uuid,
        mediaType: BackupMediaType,
        exported: ExportedAsset,
    ): HandOffResult = requests.build(row, uniqueId, mediaType, exported) { send(it) }

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
        val bundle = UserUploadBuilder.build(
            listOf(UploadSource(part.path, part.contentType, pickedName = exported.fileName, libraryKind = LibraryKind.VIDEO, untouched = untouched)),
            options,
            fileOps,
        ) { PhotobaseProtocol.VideoPayloadKey }
        val expected = if (untouched || options.originalVideo) mapOf(PhotobaseProtocol.VideoPayloadKey to expectedDigest) else emptyMap()
        val hashes = ContentHashes(PayloadHashAlgorithm.Blake3, expected) { key, chunk, offset, length ->
            if (key == PhotobaseProtocol.VideoPayloadKey) reader.feed(chunk, offset, length)
        }
        fun metadata() = reader.location.let { location ->
            val content = PhotoContent(latitude = location?.latitude, longitude = location?.longitude, altitude = location?.altitude)
            photoMetadata(uniqueId, PhotobaseProtocol.VideoDataType, row.createdAt, content)
        }
        return send(photoSpec(photosDriveId, uniqueId, bundle, metadata(), hashes, finishMetadata = { metadata() }))
    }

    private fun handedOff(outcome: UploadOutcome): HandOffResult = when (outcome) {
        is UploadOutcome.Enqueued, is UploadOutcome.AlreadyQueued -> HandOffResult.Queued
        is UploadOutcome.SourceMissing -> throw BackupEnqueueFailedException("a staged file is gone: ${outcome.missingPaths.joinToString()}")
        UploadOutcome.WouldStrandCreate -> throw BackupEnqueueFailedException("refused by the Outbox")
        is UploadOutcome.Failed -> throw BackupEnqueueFailedException("enqueue failed: ${outcome.cause.message}", outcome.cause)
    }

    private companion object {
        const val BULK_STREAMS = 2
        const val TAG = "PhotoBackup"
    }
}
