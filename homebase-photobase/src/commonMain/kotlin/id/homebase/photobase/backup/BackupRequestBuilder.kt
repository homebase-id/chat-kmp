package id.homebase.photobase.backup

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.AccessControlList
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
import id.homebase.api.image.ImageMetadata
import id.homebase.api.image.MediaQuality
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.sync.database.DeviceBackupAsset
import id.homebase.api.video.VideoQuality
import id.homebase.core.photobase.PhotobaseProtocol
import id.homebase.upload.MetadataPolicy
import id.homebase.upload.UploadOptions
import id.homebase.upload.UploadSource
import id.homebase.upload.UserUploadBuilder
import id.homebase.upload.withSealedDescriptor
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
            var exif: ImageMetadata? = null
            val bundle = timed(BackupStage.Thumbnails) {
                UserUploadBuilder.build(
                    exported.parts.map { part ->
                        val primary = part.role != ExportedPartRole.PairedVideo
                        UploadSource(part.path, part.contentType, pickedName = exported.fileName.takeIf { primary }, image = primary)
                    },
                    PHOTOS_UPLOAD_OPTIONS,
                    fileOps,
                    onImageMetadata = { _, metadata -> exif = metadata },
                ) { payloads[it].key }
            }
            val sealed = payloads.zip(bundle.payloads).mapIndexed { index, (encrypted, built) ->
                // The hash above vouches for these bytes, so the builder must not have swapped in a copy.
                check(built.filePath == exported.parts[index].path) { "the builder replaced ${built.key}" }
                val untouched = if (exported.parts[index].role == ExportedPartRole.PairedVideo) built.asUntouchedVideo() else built
                encrypted.copy(contentType = built.contentType, descriptorContent = untouched.descriptorContent).withSealedDescriptor(keyHeader.aesKey)
            }
            val metadata = photoMetadata(
                uniqueId,
                if (mediaType == BackupMediaType.LivePhoto) PhotobaseProtocol.LivePhotoDataType else PhotobaseProtocol.ImageDataType,
                captureTimeMs(exif, row.createdAt),
                PhotoContent(
                    latitude = exif?.latitude,
                    longitude = exif?.longitude,
                    altitude = exif?.altitudeMeters,
                ).withTiny(bundle.previewThumbs.firstOrNull()),
            )
            val keyByPayload = sealed.associate { it.key to KeyHeader(it.iv!!, keyHeader.aesKey) }
            val request = UploadFileRequest(
                driveId = photosDriveId,
                keyHeader = keyHeader,
                metadata = metadata.encryptContent(keyHeader),
                payloads = sealed,
                thumbnails = bundle.thumbnails.map { it.copy(thumbnailBytes = keyByPayload.getValue(it.key).encryptDataAes(it.thumbnailBytes)) },
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

    // The builder leaves a video's `o` to the step that transcodes it, and nothing transcodes a Live Photo's video.
    private fun PayloadFile.asUntouchedVideo(): PayloadFile =
        copy(descriptorContent = descriptorContent?.let(DescriptorContentV1::parseOrNull)?.copy(original = 1)?.encode())
}

// The current format ahead of the switch: only this app has ever read the Photos drive.
// Videos stay at Standard until Photos has its own video setting.
internal val PHOTOS_UPLOAD_OPTIONS = UploadOptions(
    quality = MediaQuality.ORIGINAL,
    metadata = MetadataPolicy.KEEP,
    currentFormat = true,
    videoQuality = VideoQuality.STANDARD,
)

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
