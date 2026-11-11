package id.homebase.photobase.backup

import co.touchlab.kermit.Logger
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.AccessControlList
import id.homebase.api.client.drives.files.PayloadFile
import id.homebase.api.client.drives.files.PayloadHashAlgorithm
import id.homebase.api.client.drives.files.SecurityGroupType
import id.homebase.api.client.drives.upload.UploadAppFileMetaData
import id.homebase.api.client.drives.upload.UploadFileMetadata
import id.homebase.api.client.drives.upload.UploadFileRequest
import id.homebase.api.crypto.encryptWithPayloadHash
import id.homebase.api.crypto.plaintextContentHash
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.image.MediaImages
import id.homebase.api.image.createMediaImages
import id.homebase.api.image.readImageMetadata
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.sync.database.DeviceBackupAsset
import id.homebase.api.util.truncateToCodePoints
import id.homebase.core.photobase.PhotobaseProtocol
import kotlinx.coroutines.CancellationException
import kotlin.uuid.Uuid

/** One photo as an upload request: original bytes encrypted to staged files, however it's then sent. */
class BackupRequestBuilder(
    private val fileOps: FileOperationsProvider,
    private val photosDriveId: Uuid = PhotobaseProtocol.DriveAlias,
) {
    /** Null when the bytes no longer match the hash; nothing is left behind then. On success the caller owns the staged files. */
    suspend fun build(row: DeviceBackupAsset, uniqueId: Uuid, mediaType: BackupMediaType, exported: ExportedAsset): UploadFileRequest? {
        val digests = digestsOf(row)
        require(exported.parts.size == digests.size) { "${exported.parts.size} parts against ${digests.size} digests" }
        val keyHeader = KeyHeader.newRandom16()
        val payloads = mutableListOf<PayloadFile>()
        var complete = false
        try {
            for ((index, part) in exported.parts.withIndex()) {
                val encryptedPath = fileOps.createOutboxStagingPath("photo", ".encrypted")
                payloads += PayloadFile(key = keyFor(part.role), filePath = encryptedPath, contentType = part.contentType, isPreEncrypted = true)
                val hashed = timed(BackupStage.Encrypt) {
                    fileOps.encryptWithPayloadHash(part.path, encryptedPath, keyHeader.aesKey, PayloadHashAlgorithm.Blake3)
                }
                val digest = hashed.hash.plaintextContentHash(KeyHeader(hashed.iv, keyHeader.aesKey))
                if (digest == null || !digest.contentEquals(digests[index])) return null
                payloads[index] = payloads[index].copy(iv = hashed.iv, hash = hashed.hash)
            }
            val primaryKey = KeyHeader(payloads.first().iv!!, keyHeader.aesKey)
            val imagePath = exported.parts.first().path
            val (prepared, exif) = timed(BackupStage.Thumbnails) {
                val media = prepareImage(imagePath, payloads.first().key)
                media to (media?.metadata ?: readExifSoftly(imagePath))
            }
            val metadata = photoMetadata(
                uniqueId,
                if (mediaType == BackupMediaType.LivePhoto) PhotobaseProtocol.LivePhotoDataType else PhotobaseProtocol.ImageDataType,
                captureTimeMs(exif, row.createdAt),
                PhotoContent(
                    name = exported.fileName.truncateToCodePoints(MAX_PHOTO_NAME_CODE_POINTS),
                    latitude = exif?.latitude,
                    longitude = exif?.longitude,
                    altitude = exif?.altitudeMeters,
                    width = prepared?.displayedSize?.pixelWidth,
                    height = prepared?.displayedSize?.pixelHeight,
                ).withTiny(prepared?.tiny),
            )
            val request = UploadFileRequest(
                driveId = photosDriveId,
                keyHeader = keyHeader,
                metadata = metadata.encryptContent(keyHeader),
                payloads = payloads.toList(),
                thumbnails = prepared?.thumbnails.orEmpty().map { it.copy(thumbnailBytes = primaryKey.encryptDataAes(it.thumbnailBytes)) },
                existingFileIsSuccess = true,
            )
            complete = true
            return request
        } finally {
            if (!complete) payloads.forEach { runCatching { fileOps.deleteTempFile(it.filePath) } }
        }
    }

    private fun keyFor(role: ExportedPartRole) =
        if (role == ExportedPartRole.PairedVideo) PhotobaseProtocol.LivePhotoVideoPayloadKey else PhotobaseProtocol.ImagePayloadKey

    // A format the decoder can't read is still worth keeping: the bytes back up without previews.
    private suspend fun prepareImage(path: String, payloadKey: String): MediaImages? = try {
        createMediaImages(path, fileOps, payloadKey, readMetadata = true)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Logger.w(tag = TAG, throwable = e) { "no thumbnails for $payloadKey" }
        null
    }

    private suspend fun readExifSoftly(path: String) = try {
        readImageMetadata(path, fileOps)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Logger.w(tag = TAG, throwable = e) { "no readable EXIF" }
        null
    }

    private companion object {
        const val TAG = "BackupRequestBuilder"
    }
}

@OptIn(ExperimentalStdlibApi::class)
internal fun digestsOf(row: DeviceBackupAsset): List<ByteArray> =
    row.contentHash.orEmpty().split(HashedAsset.CONTENT_HASH_SEPARATOR).map { it.hexToByteArray() }

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
