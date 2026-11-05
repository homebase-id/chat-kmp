package id.homebase.photobase.backup

import co.touchlab.kermit.Logger
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.AccessControlList
import id.homebase.api.client.drives.files.PayloadFile
import id.homebase.api.client.drives.files.PayloadHashAlgorithm
import id.homebase.api.client.drives.files.SecurityGroupType
import id.homebase.api.client.drives.files.ThumbnailFile
import id.homebase.api.client.drives.upload.EmbeddedThumb
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
                payloads += PayloadFile(key = keyFor(part.role, mediaType), filePath = encryptedPath, contentType = part.contentType, isPreEncrypted = true)
                val hashed = fileOps.encryptWithPayloadHash(part.path, encryptedPath, keyHeader.aesKey, PayloadHashAlgorithm.Blake3)
                val digest = hashed.hash.plaintextContentHash(KeyHeader(hashed.iv, keyHeader.aesKey))
                if (digest == null || !digest.contentEquals(digests[index])) return null
                payloads[index] = payloads[index].copy(iv = hashed.iv, hash = hashed.hash)
            }
            val primaryKey = KeyHeader(payloads.first().iv!!, keyHeader.aesKey)
            val imageBytes = fileOps.readFileBytes(exported.parts.first().path)
            val prepared = prepareImage(imageBytes, payloads.first().key)
            val exif = readExifSoftly(imageBytes)
            val metadata = photoMetadata(
                uniqueId,
                if (mediaType == BackupMediaType.LivePhoto) PhotobaseProtocol.LivePhotoDataType else PhotobaseProtocol.ImageDataType,
                captureTimeMs(exif, row.createdAt),
                PhotoContent(
                    name = exported.fileName.truncateToCodePoints(MAX_NAME_CODE_POINTS),
                    latitude = exif?.latitude,
                    longitude = exif?.longitude,
                    altitude = exif?.altitudeMeters,
                    width = prepared?.width,
                    height = prepared?.height,
                    tiny = prepared?.tiny?.content,
                    tinyWidth = prepared?.tiny?.pixelWidth,
                    tinyHeight = prepared?.tiny?.pixelHeight,
                    tinyType = prepared?.tiny?.contentType,
                ),
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

    private fun keyFor(role: ExportedPartRole, mediaType: BackupMediaType) = when {
        role == ExportedPartRole.PairedVideo -> PhotobaseProtocol.LivePhotoVideoPayloadKey
        mediaType == BackupMediaType.Video -> PhotobaseProtocol.VideoPayloadKey
        else -> PhotobaseProtocol.ImagePayloadKey
    }

    private class PreparedImage(val width: Int, val height: Int, val tiny: EmbeddedThumb?, val thumbnails: List<ThumbnailFile>)

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

    private fun readExifSoftly(imageBytes: ByteArray) = try {
        readImageMetadata(imageBytes)
    } catch (e: Exception) {
        Logger.w(tag = TAG, throwable = e) { "no readable EXIF" }
        null
    }

    private companion object {
        const val TAG = "BackupRequestBuilder"
        const val MAX_NAME_CODE_POINTS = 200
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
