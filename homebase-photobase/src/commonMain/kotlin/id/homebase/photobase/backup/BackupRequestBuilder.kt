package id.homebase.photobase.backup

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.AccessControlList
import id.homebase.api.client.drives.files.DescriptorContentSeal
import id.homebase.api.client.drives.files.DescriptorContentV1
import id.homebase.api.client.drives.files.PayloadFile
import id.homebase.api.client.drives.files.PayloadHashAlgorithm
import id.homebase.api.client.drives.files.SecurityGroupType
import id.homebase.api.client.drives.upload.UploadAppFileMetaData
import id.homebase.api.client.drives.upload.UploadFileMetadata
import id.homebase.api.client.drives.upload.UploadFileRequest
import id.homebase.api.crypto.encryptWithPayloadHash
import id.homebase.api.crypto.plaintextContentHash
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.image.MediaQuality
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.sync.database.DeviceBackupAsset
import id.homebase.core.photobase.PhotobaseProtocol
import id.homebase.upload.MetadataPolicy
import id.homebase.upload.UploadOptions
import id.homebase.upload.UploadSource
import id.homebase.upload.UserUploadBuilder
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
            val bundle = timed(BackupStage.Thumbnails) {
                UserUploadBuilder.build(
                    exported.parts.map { part ->
                        val primary = part.role != ExportedPartRole.PairedVideo
                        UploadSource(part.path, part.contentType, pickedName = exported.fileName.takeIf { primary }, image = primary)
                    },
                    PHOTO_OPTIONS,
                    fileOps,
                ) { keyFor(exported.parts[it].role) }
            }
            val sealed = payloads.zip(bundle.payloads).mapIndexed { index, (encrypted, built) ->
                // The hash above vouches for these bytes, so the builder must not have swapped in a copy.
                check(built.filePath == exported.parts[index].path) { "the builder replaced ${built.key}" }
                encrypted.copy(
                    contentType = built.contentType,
                    descriptorContent = sealedDescriptor(built, keyHeader),
                    stripMetadata = built.stripMetadata,
                )
            }
            val exif = bundle.imageMetadata[PhotobaseProtocol.ImagePayloadKey]
            val metadata = photoMetadata(
                uniqueId,
                if (mediaType == BackupMediaType.LivePhoto) PhotobaseProtocol.LivePhotoDataType else PhotobaseProtocol.ImageDataType,
                captureTimeMs(exif, row.createdAt),
                PhotoContent(
                    latitude = exif?.latitude,
                    longitude = exif?.longitude,
                    altitude = exif?.altitudeMeters,
                    cameraMake = exif?.cameraMake,
                    cameraModel = exif?.cameraModel,
                ).withTiny(bundle.payloads.first().previewThumbnail),
            )
            val ivByKey = sealed.associate { it.key to it.iv!! }
            val request = UploadFileRequest(
                driveId = photosDriveId,
                keyHeader = keyHeader,
                metadata = metadata.encryptContent(keyHeader),
                payloads = sealed,
                thumbnails = bundle.thumbnails.map {
                    it.copy(thumbnailBytes = KeyHeader(ivByKey.getValue(it.key), keyHeader.aesKey).encryptDataAes(it.thumbnailBytes))
                },
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

    // Nothing here is transcoded, so every part is the user's own file, the paired video included.
    private suspend fun sealedDescriptor(built: PayloadFile, keyHeader: KeyHeader): String? {
        val content = built.descriptorContent?.let(DescriptorContentV1::parseOrNull) ?: return null
        return DescriptorContentSeal.seal(content.copy(original = 1).encode(), keyHeader.aesKey)
    }

    private companion object {
        // The Photos drive has only ever been read by this app, so it writes the current format ahead of the switch.
        val PHOTO_OPTIONS = UploadOptions(
            quality = MediaQuality.ORIGINAL,
            metadata = MetadataPolicy.KEEP,
            currentFormat = true,
            readImageMetadata = true,
        )
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
