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
import id.homebase.api.crypto.encryptWithPayloadHash
import id.homebase.api.crypto.plaintextContentHash
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.image.ImageMetadata
import id.homebase.api.image.MediaQuality
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.sync.database.DeviceBackupAsset
import id.homebase.api.sync.database.OUTBOX_BULK_PRIORITY
import id.homebase.api.video.VideoQuality
import id.homebase.core.photobase.PhotobaseProtocol
import id.homebase.upload.LibraryKind
import id.homebase.upload.MediaUploadSpec
import id.homebase.upload.MetadataPolicy
import id.homebase.upload.PayloadBundle
import id.homebase.upload.UploadOptions
import id.homebase.upload.UploadSource
import id.homebase.upload.UserUploadBuilder
import kotlin.uuid.Uuid

/** One photo as an upload: its bytes, at the photo setting's quality, encrypted to staged files, however it's then sent. */
class BackupRequestBuilder(
    private val fileOps: FileOperationsProvider,
    private val hasher: AssetHasher,
    private val settings: () -> BackupSettings = { BackupSettings() },
    private val photosDriveId: Uuid = PhotobaseProtocol.DriveAlias,
) {
    /** The current format ahead of the switch: only this app has ever read the Photos drive. Metadata is always kept. */
    fun options(): UploadOptions = settings().let { s ->
        UploadOptions(
            quality = s.photoQuality,
            metadata = MetadataPolicy.KEEP,
            currentFormat = true,
            videoQuality = s.videoQuality.toVideoQuality(),
            originalVideo = s.videoQuality == MediaQuality.ORIGINAL,
        )
    }

    /** Null when the bytes no longer match the hash; nothing is left behind then. On success the caller owns the staged files. */
    suspend fun build(row: DeviceBackupAsset, uniqueId: Uuid, mediaType: BackupMediaType, exported: ExportedAsset): MediaUploadSpec? {
        val digests = digestsOf(row)
        require(exported.parts.size == digests.size) { "${exported.parts.size} parts against ${digests.size} digests" }
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
                    // A Live Photo's video is its own file, encrypted here; it never gets a copy.
                    options().copy(originalVideo = false),
                    fileOps,
                    onImageMetadata = { _, metadata -> exif = metadata },
                ) { keyFor(exported.parts[it].role) }
            }
            for ((index, built) in bundle.payloads.withIndex()) {
                val part = exported.parts[index]
                // A part sent as it is is vouched for by its encrypted hash below; a re-encode by its source's hash.
                val untouched = built.filePath == part.path
                if (!untouched) {
                    reencoded += built.filePath
                    if (!hasher.matches(exported, part, digests[index])) return null
                }
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
            complete = true
            return photoSpec(photosDriveId, uniqueId, keyHeader, bundle.copy(payloads = payloads.toList()), metadata)
        } finally {
            reencoded.forEach { runCatching { fileOps.deleteTempFile(it) } }
            if (!complete) payloads.forEach { runCatching { fileOps.deleteTempFile(it.filePath) } }
        }
    }

    private fun keyFor(role: ExportedPartRole) =
        if (role == ExportedPartRole.PairedVideo) PhotobaseProtocol.LivePhotoVideoPayloadKey else PhotobaseProtocol.ImagePayloadKey
}

@OptIn(ExperimentalStdlibApi::class)
internal fun digestsOf(row: DeviceBackupAsset): List<ByteArray> =
    row.contentHash.orEmpty().split(HashedAsset.CONTENT_HASH_SEPARATOR).map { it.hexToByteArray() }

/** Bulk, and never shown from a local row: the Photos app shows the library itself. */
internal fun photoSpec(driveId: Uuid, uniqueId: Uuid, keyHeader: KeyHeader, bundle: PayloadBundle, metadata: UploadFileMetadata) = MediaUploadSpec(
    driveId = driveId,
    uniqueId = uniqueId,
    keyHeader = keyHeader,
    bundle = bundle,
    metadata = metadata,
    priority = OUTBOX_BULK_PRIORITY,
    sendNow = false,
    writeOptimistic = false,
    seedCache = false,
    existingFileIsSuccess = true,
    sealDescriptorContent = true,
)

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
