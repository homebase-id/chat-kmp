package id.homebase.photobase.backup

import co.touchlab.kermit.Logger
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.AccessControlList
import id.homebase.api.client.drives.files.PayloadFile
import id.homebase.api.client.drives.files.PayloadHashAlgorithm
import id.homebase.api.client.drives.files.SecurityGroupType
import id.homebase.api.client.drives.files.ThumbnailFile
import id.homebase.api.client.drives.upload.UploadAppFileMetaData
import id.homebase.api.client.drives.upload.UploadFileMetadata
import id.homebase.api.client.drives.upload.UploadFileRequest
import id.homebase.api.crypto.encryptWithPayloadHash
import id.homebase.api.crypto.plaintextContentHash
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.image.createThumbnails
import id.homebase.api.image.readImageMetadata
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.sync.database.DeviceBackupAsset
import id.homebase.api.sync.database.EnqueueResult
import id.homebase.api.sync.database.OUTBOX_BULK_PRIORITY
import id.homebase.api.sync.database.OutboxSync
import id.homebase.api.util.truncateToCodePoints
import id.homebase.core.photobase.PhotobaseProtocol
import id.homebase.upload.MediaUploadSpec
import id.homebase.upload.PayloadBundle
import id.homebase.upload.UploadOutcome
import id.homebase.upload.UploadService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.uuid.Uuid

class BackupEnqueueFailedException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** One Homebase file, built for the Photos drive: original bytes, no optimistic row, 4105 counts as sent. */
class OutboxBackupUploader(
    private val fileOps: FileOperationsProvider,
    private val source: DeviceChangeSource,
    private val hasher: AssetHasher,
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
            val digests = row.contentHash.orEmpty().split(HashedAsset.CONTENT_HASH_SEPARATOR).map { it.hexToBytes() }
            return if (mediaType == BackupMediaType.Video) {
                videoProcessing.withLock { handOffVideo(row, uniqueId, exported, digests.single()) }
            } else {
                handOffOriginal(row, uniqueId, mediaType, exported, digests)
            }
        } finally {
            exported.deleteTemporaryParts(fileOps)
        }
    }

    override suspend fun isStillSending(row: DeviceBackupAsset, uniqueId: Uuid): Boolean =
        outboxSync.pendingUploadType(photosDriveId, uniqueId) != null

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
        digests: List<ByteArray>,
    ): HandOffResult {
        require(exported.parts.size == digests.size) { "${exported.parts.size} parts against ${digests.size} digests" }
        val keyHeader = KeyHeader.newRandom16()
        val payloads = mutableListOf<PayloadFile>()
        var queued = false
        try {
            for ((index, part) in exported.parts.withIndex()) {
                val encryptedPath = fileOps.createOutboxStagingPath("photo", ".encrypted")
                payloads += PayloadFile(key = keyFor(part.role, mediaType), filePath = encryptedPath, contentType = part.contentType, isPreEncrypted = true)
                val hashed = fileOps.encryptWithPayloadHash(part.path, encryptedPath, keyHeader.aesKey, PayloadHashAlgorithm.Blake3)
                val digest = hashed.hash.plaintextContentHash(KeyHeader(hashed.iv, keyHeader.aesKey))
                if (digest == null || !digest.contentEquals(digests[index])) return HandOffResult.ChangedUnderUs
                payloads[index] = payloads[index].copy(iv = hashed.iv, hash = hashed.hash)
            }

            val primary = exported.parts.first()
            val primaryKey = KeyHeader(payloads.first().iv!!, keyHeader.aesKey)
            val imageBytes = fileOps.readFileBytes(primary.path)
            val prepared = prepareImage(imageBytes, payloads.first().key)
            val metadataOfImage = readMetadataSoftly(imageBytes)
            val content = PhotoContent(
                name = exported.fileName.truncateToCodePoints(MAX_NAME_CODE_POINTS),
                latitude = metadataOfImage?.latitude,
                longitude = metadataOfImage?.longitude,
                altitude = metadataOfImage?.altitudeMeters,
                width = prepared?.width,
                height = prepared?.height,
                tiny = prepared?.tiny?.content,
                tinyWidth = prepared?.tiny?.pixelWidth,
                tinyHeight = prepared?.tiny?.pixelHeight,
                tinyType = prepared?.tiny?.contentType,
            )
            val metadata = photoMetadata(
                uniqueId,
                if (mediaType == BackupMediaType.LivePhoto) PhotobaseProtocol.LivePhotoDataType else PhotobaseProtocol.ImageDataType,
                captureTimeMs(metadataOfImage, row.createdAt),
                content,
            )
            val request = UploadFileRequest(
                driveId = photosDriveId,
                keyHeader = keyHeader,
                metadata = metadata.encryptContent(keyHeader),
                payloads = payloads,
                thumbnails = prepared?.thumbnails.orEmpty().map { it.copy(thumbnailBytes = primaryKey.encryptDataAes(it.thumbnailBytes)) },
                existingFileIsSuccess = true,
            )
            when (val enqueue = outboxSync.tryEnqueue(request, priority = OUTBOX_BULK_PRIORITY, sendNow = false)) {
                EnqueueResult.Enqueued -> queued = true
                EnqueueResult.AlreadyQueued -> Unit
                EnqueueResult.WouldStrandCreate -> throw BackupEnqueueFailedException("refused by the Outbox")
                is EnqueueResult.Failed -> throw BackupEnqueueFailedException("enqueue failed: ${enqueue.cause.message}", enqueue.cause)
            }
            return HandOffResult.Queued
        } finally {
            if (!queued) payloads.forEach { runCatching { fileOps.deleteTempFile(it.filePath) } }
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
        if (!hasher.digest(part.path).contentEquals(expectedDigest)) return HandOffResult.ChangedUnderUs
        val spec = MediaUploadSpec(
            driveId = photosDriveId,
            uniqueId = uniqueId,
            keyHeader = KeyHeader.newRandom16(),
            bundle = PayloadBundle(
                payloads = listOf(PayloadFile(key = PhotobaseProtocol.VideoPayloadKey, filePath = part.path, contentType = part.contentType)),
                thumbnails = emptyList(),
                previewThumbs = emptyList(),
            ),
            metadata = photoMetadata(
                uniqueId,
                PhotobaseProtocol.VideoDataType,
                row.createdAt,
                PhotoContent(name = exported.fileName.truncateToCodePoints(MAX_NAME_CODE_POINTS)),
            ),
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

    private fun keyFor(role: ExportedPartRole, mediaType: BackupMediaType) = when {
        role == ExportedPartRole.PairedVideo -> PhotobaseProtocol.LivePhotoVideoPayloadKey
        mediaType == BackupMediaType.Video -> PhotobaseProtocol.VideoPayloadKey
        else -> PhotobaseProtocol.ImagePayloadKey
    }

    private class PreparedImage(
        val width: Int,
        val height: Int,
        val tiny: id.homebase.api.client.drives.upload.EmbeddedThumb?,
        val thumbnails: List<ThumbnailFile>,
    )

    // A format the decoder can't read is still worth keeping: the bytes back up without previews.
    private suspend fun prepareImage(imageBytes: ByteArray, payloadKey: String): PreparedImage? = try {
        val (size, tiny, thumbnails) = createThumbnails(imageBytes, payloadKey)
        PreparedImage(size.pixelWidth, size.pixelHeight, tiny, thumbnails)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Logger.w(tag = TAG, throwable = e) { "no thumbnails for $payloadKey" }
        null
    }

    private fun readMetadataSoftly(imageBytes: ByteArray) = try {
        readImageMetadata(imageBytes)
    } catch (e: Exception) {
        Logger.w(tag = TAG, throwable = e) { "no readable EXIF" }
        null
    }

    private companion object {
        const val TAG = "OutboxBackupUploader"
        const val BULK_STREAMS = 2
        const val MAX_NAME_CODE_POINTS = 200
    }
}

internal fun photoMetadata(uniqueId: Uuid, dataType: Int, userDateMs: Long, content: PhotoContent) = UploadFileMetadata(
    allowDistribution = false,
    isEncrypted = true,
    accessControlList = AccessControlList(requiredSecurityGroup = SecurityGroupType.Owner.value),
    appData = UploadAppFileMetaData(
        uniqueId = uniqueId,
        fileType = PhotobaseProtocol.PhotoFileType,
        dataType = dataType,
        userDate = userDateMs,
        content = OdinSystemSerializer.serialize(content),
    ),
)

@OptIn(ExperimentalStdlibApi::class)
private fun String.hexToBytes(): ByteArray = hexToByteArray()
