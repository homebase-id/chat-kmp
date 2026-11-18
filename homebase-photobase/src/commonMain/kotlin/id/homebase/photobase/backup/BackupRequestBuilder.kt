package id.homebase.photobase.backup

import id.homebase.api.video.toVideoQuality
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.AccessControlList
import id.homebase.api.client.drives.files.PayloadHashAlgorithm
import id.homebase.api.client.drives.files.SecurityGroupType
import id.homebase.api.client.drives.upload.EmbeddedThumb
import id.homebase.api.client.drives.upload.UploadAppFileMetaData
import id.homebase.api.client.drives.upload.UploadFileMetadata
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.media.MediaMetadataReader
import id.homebase.api.image.MediaQuality
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.sync.database.DeviceBackupAsset
import id.homebase.api.sync.database.OUTBOX_BULK_PRIORITY
import id.homebase.api.video.VideoQuality
import id.homebase.core.photobase.PhotobaseProtocol
import id.homebase.upload.ContentHashes
import id.homebase.upload.LibraryKind
import id.homebase.upload.MediaUploadSpec
import id.homebase.upload.MetadataPolicy
import id.homebase.upload.PayloadBundle
import id.homebase.upload.UploadOptions
import id.homebase.upload.UploadSource
import id.homebase.upload.UserUploadBuilder
import kotlin.uuid.Uuid

/** One photo as an upload: its bytes at the photo setting's quality, however it's then sent. */
class BackupRequestBuilder(
    private val fileOps: FileOperationsProvider,
    private val hasher: AssetHasher,
    private val settings: () -> BackupSettings = { BackupSettings() },
    private val photosDriveId: Uuid = PhotobaseProtocol.DriveAlias,
) {
    private val metadataReader = MediaMetadataReader(fileOps)

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

    /**
     * [send] gets the photo's upload, with each part the builder left as it is checked against its hash as it's
     * encrypted. [HandOffResult.ChangedUnderUs], without calling [send], when a re-encoded part's source no longer matches.
     */
    suspend fun build(
        row: DeviceBackupAsset,
        uniqueId: Uuid,
        mediaType: BackupMediaType,
        exported: ExportedAsset,
        send: suspend (MediaUploadSpec) -> HandOffResult,
    ): HandOffResult {
        val digests = digestsOf(row)
        require(exported.parts.size == digests.size) { "${exported.parts.size} parts against ${digests.size} digests" }
        val reencoded = mutableListOf<String>()
        try {
            val bundle = timed(BackupStage.Thumbnails) {
                UserUploadBuilder.build(
                    exported.parts.map { part ->
                        if (part.role == ExportedPartRole.PairedVideo) {
                            UploadSource(part.path, part.contentType, libraryKind = LibraryKind.VIDEO, untouched = true)
                        } else {
                            UploadSource(part.path, part.contentType, pickedName = exported.fileName, libraryKind = LibraryKind.IMAGE)
                        }
                    },
                    // A Live Photo's video is sent as it is; it never gets a copy.
                    options().copy(originalVideo = false),
                    fileOps,
                ) { keyFor(exported.parts[it].role) }
            }
            val expected = mutableMapOf<String, ByteArray>()
            for ((index, built) in bundle.payloads.withIndex()) {
                val part = exported.parts[index]
                if (built.filePath == part.path) {
                    expected[built.key] = digests[index]
                } else {
                    reencoded += built.filePath
                    if (!hasher.matches(exported, part, digests[index])) return HandOffResult.ChangedUnderUs
                }
            }
            val exif = timed(BackupStage.Thumbnails) { exported.parts.firstOrNull { it.role == ExportedPartRole.Primary }?.let { metadataReader.image(it.path) } }
            val metadata = photoMetadata(
                uniqueId,
                if (mediaType == BackupMediaType.LivePhoto) PhotobaseProtocol.LivePhotoDataType else PhotobaseProtocol.ImageDataType,
                captureTimeMs(exif, row.createdAt),
                PhotoContent(latitude = exif?.latitude, longitude = exif?.longitude, altitude = exif?.altitudeMeters),
                bundle.previewThumbs.firstOrNull(),
            )
            return send(photoSpec(photosDriveId, uniqueId, bundle, metadata, ContentHashes(PayloadHashAlgorithm.Blake3, expected)))
        } finally {
            reencoded.forEach { runCatching { fileOps.deleteTempFile(it) } }
        }
    }

    private fun keyFor(role: ExportedPartRole) =
        if (role == ExportedPartRole.PairedVideo) PhotobaseProtocol.LivePhotoVideoPayloadKey else PhotobaseProtocol.ImagePayloadKey
}

@OptIn(ExperimentalStdlibApi::class)
internal fun digestsOf(row: DeviceBackupAsset): List<ByteArray> =
    row.contentHash.orEmpty().split(HashedAsset.CONTENT_HASH_SEPARATOR).map { it.hexToByteArray() }

/** Bulk, and never shown from a local row: the Photos app shows the library itself. Every payload carries its hash. */
internal fun photoSpec(
    driveId: Uuid,
    uniqueId: Uuid,
    bundle: PayloadBundle,
    metadata: UploadFileMetadata,
    hashes: ContentHashes,
) = MediaUploadSpec(
    driveId = driveId,
    uniqueId = uniqueId,
    keyHeader = KeyHeader.newRandom16(),
    bundle = bundle,
    metadata = metadata,
    priority = OUTBOX_BULK_PRIORITY,
    sendNow = false,
    writeOptimistic = false,
    seedCache = false,
    existingFileIsSuccess = true,
    sealDescriptorContent = true,
    contentHashes = hashes,
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
