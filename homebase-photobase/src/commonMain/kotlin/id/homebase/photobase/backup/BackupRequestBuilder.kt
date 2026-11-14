package id.homebase.photobase.backup

import id.homebase.api.video.toVideoQuality
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.AccessControlList
import id.homebase.api.client.drives.files.PayloadFile
import id.homebase.api.client.drives.files.PayloadHashAlgorithm
import id.homebase.api.client.drives.files.SecurityGroupType
import id.homebase.api.client.drives.upload.EmbeddedThumb
import id.homebase.api.client.drives.upload.UploadAppFileMetaData
import id.homebase.api.client.drives.upload.UploadFileMetadata
import id.homebase.api.client.drives.upload.UploadFileRequest
import id.homebase.api.crypto.encryptWithPayloadHash
import id.homebase.api.crypto.plaintextContentHash
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.image.ImageMetadata
import id.homebase.api.image.MediaQuality
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.sync.database.DeviceBackupAsset
import id.homebase.api.video.VideoQuality
import id.homebase.core.photobase.PhotobaseProtocol
import id.homebase.upload.LibraryKind
import id.homebase.upload.MetadataPolicy
import id.homebase.upload.UploadOptions
import id.homebase.upload.UploadSource
import id.homebase.upload.UserUploadBuilder
import kotlin.uuid.Uuid

/** One photo as an upload request: its bytes, at the photo setting's quality, encrypted to staged files, however it's then sent. */
class BackupRequestBuilder(
    private val fileOps: FileOperationsProvider,
    /** The source's content hash, to check it before a re-encode, whose bytes the server's hash can't vouch for. */
    private val digestOf: suspend (path: String) -> ByteArray,
    private val photoQuality: () -> MediaQuality = { MediaQuality.ORIGINAL },
    private val photosDriveId: Uuid = PhotobaseProtocol.DriveAlias,
) {
    /** Null when the bytes no longer match the hash; nothing is left behind then. On success the caller owns the staged files. */
    suspend fun build(row: DeviceBackupAsset, uniqueId: Uuid, mediaType: BackupMediaType, exported: ExportedAsset): UploadFileRequest? {
        val digests = digestsOf(row)
        require(exported.parts.size == digests.size) { "${exported.parts.size} parts against ${digests.size} digests" }
        val quality = photoQuality()
        val still = exported.parts.first()
        if (quality != MediaQuality.ORIGINAL && !exported.isHashedCopy && !digestOf(still.path).contentEquals(digests.first())) return null
        val keyHeader = KeyHeader.newRandom16()
        val payloads = mutableListOf<PayloadFile>()
        val reencoded = mutableListOf<String>()
        var complete = false
        try {
            var exif: ImageMetadata? = null
            val bundle = timed(BackupStage.Thumbnails) {
                UserUploadBuilder.build(
                    exported.parts.map { part ->
                        if (part.role == ExportedPartRole.PairedVideo) {
                            UploadSource(part.path, part.contentType, libraryKind = LibraryKind.VIDEO, untouched = true)
                        } else {
                            UploadSource(part.path, part.contentType, pickedName = exported.fileName, libraryKind = LibraryKind.IMAGE)
                        }
                    },
                    photosUploadOptions(photo = quality),
                    fileOps,
                    onImageMetadata = { _, metadata -> exif = metadata },
                ) { keyFor(exported.parts[it].role) }
            }
            for ((index, built) in bundle.payloads.withIndex()) {
                val untouched = built.filePath == exported.parts[index].path
                if (!untouched) reencoded += built.filePath
                val encryptedPath = fileOps.createOutboxStagingPath("photo", ".encrypted")
                payloads += built.copy(filePath = encryptedPath, isPreEncrypted = true)
                val hashed = timed(BackupStage.Encrypt) {
                    fileOps.encryptWithPayloadHash(built.filePath, encryptedPath, keyHeader.aesKey, PayloadHashAlgorithm.Blake3)
                }
                if (untouched) {
                    val digest = hashed.hash.plaintextContentHash(KeyHeader(hashed.iv, keyHeader.aesKey))
                    if (digest == null || !digest.contentEquals(digests[index])) return null
                }
                payloads[index] = payloads[index].copy(iv = hashed.iv, hash = hashed.hash)
            }
            val metadata = photoMetadata(
                uniqueId,
                if (mediaType == BackupMediaType.LivePhoto) PhotobaseProtocol.LivePhotoDataType else PhotobaseProtocol.ImageDataType,
                captureTimeMs(exif, row.createdAt),
                PhotoContent(latitude = exif?.latitude, longitude = exif?.longitude, altitude = exif?.altitudeMeters),
                bundle.previewThumbs.firstOrNull(),
            )
            val keyByPayload = payloads.associate { it.key to KeyHeader(it.iv!!, keyHeader.aesKey) }
            val request = UploadFileRequest(
                driveId = photosDriveId,
                keyHeader = keyHeader,
                metadata = metadata.encryptContent(keyHeader),
                payloads = payloads.toList(),
                thumbnails = bundle.thumbnails.map { it.copy(thumbnailBytes = keyByPayload.getValue(it.key).encryptDataAes(it.thumbnailBytes)) },
                existingFileIsSuccess = true,
                sealDescriptorContent = true,
            )
            complete = true
            return request
        } finally {
            reencoded.forEach { runCatching { fileOps.deleteTempFile(it) } }
            if (!complete) payloads.forEach { runCatching { fileOps.deleteTempFile(it.filePath) } }
        }
    }

    private fun keyFor(role: ExportedPartRole) =
        if (role == ExportedPartRole.PairedVideo) PhotobaseProtocol.LivePhotoVideoPayloadKey else PhotobaseProtocol.ImagePayloadKey
}

// The current format ahead of the switch: only this app has ever read the Photos drive. Metadata is always kept.
internal fun photosUploadOptions(photo: MediaQuality = MediaQuality.ORIGINAL, video: MediaQuality = MediaQuality.HD) = UploadOptions(
    quality = photo,
    metadata = MetadataPolicy.KEEP,
    currentFormat = true,
    videoQuality = video.toVideoQuality(),
    originalVideo = video == MediaQuality.ORIGINAL,
)

@OptIn(ExperimentalStdlibApi::class)
internal fun digestsOf(row: DeviceBackupAsset): List<ByteArray> =
    row.contentHash.orEmpty().split(HashedAsset.CONTENT_HASH_SEPARATOR).map { it.hexToByteArray() }

internal fun photoMetadata(uniqueId: Uuid, dataType: Int, userDateMs: Long, content: PhotoContent, tiny: EmbeddedThumb? = null) = UploadFileMetadata(
    allowDistribution = false,
    isEncrypted = true,
    accessControlList = AccessControlList(requiredSecurityGroup = SecurityGroupType.Owner.value),
    appData = UploadAppFileMetaData(
        uniqueId = uniqueId,
        fileType = PhotobaseProtocol.PhotoFileType,
        dataType = dataType,
        userDate = userDateMs,
        content = OdinSystemSerializer.serialize(content),
        previewThumbnail = tiny,
    ),
)
