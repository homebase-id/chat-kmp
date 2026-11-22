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
import co.touchlab.kermit.Logger
import id.homebase.api.media.MediaMetadataReader
import id.homebase.api.media.MotionPhoto
import id.homebase.api.video.VideoCompressionFailedException
import id.homebase.api.image.MediaQuality
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.sync.database.DeviceBackupAsset
import id.homebase.api.sync.database.OUTBOX_BULK_PRIORITY
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
     * A Live or motion photo's clip goes as `photo_live` (UPLOADS.md section 3.7); when it can't be transcoded the
     * photo goes as a still, keeping an iPhone's paired video untouched at Original.
     */
    suspend fun build(
        row: DeviceBackupAsset,
        uniqueId: Uuid,
        exported: ExportedAsset,
        send: suspend (MediaUploadSpec) -> HandOffResult,
    ): HandOffResult {
        val digests = digestsOf(row)
        require(exported.parts.size == digests.size) { "${exported.parts.size} parts against ${digests.size} digests" }
        val temps = mutableListOf<String>()
        try {
            val paired = exported.parts.indexOfFirst { it.role == ExportedPartRole.PairedVideo }.takeIf { it >= 0 }
            val clip = paired?.let { Clip(exported.parts[it].path, exported.parts[it].contentType, part = it) }
                ?: cutClip(exported, exported.primaryIndex())?.also { temps += it.path }
            return try {
                build(row, uniqueId, exported, digests, clip, temps, send)
            } catch (e: VideoCompressionFailedException) {
                if (clip == null) throw e
                Logger.w(tag = TAG, throwable = e) { "no clip for $uniqueId; backing it up as a still" }
                val keptAsItIs = clip.takeIf { it.paired && settings().photoQuality == MediaQuality.ORIGINAL }?.copy(untouched = true)
                build(row, uniqueId, exported, digests, keptAsItIs, temps, send)
            }
        } finally {
            temps.forEach { runCatching { fileOps.deleteTempFile(it) } }
        }
    }

    /** [part] is the exported part the clip is, or comes from; [untouched]: sent as it is, with no copy to play. */
    private data class Clip(val path: String, val contentType: String, val part: Int, val paired: Boolean = true, val untouched: Boolean = false)

    private suspend fun cutClip(exported: ExportedAsset, index: Int): Clip? {
        val primary = exported.parts[index]
        if (primary.contentType !in MOTION_PHOTO_TYPES) return null
        val path = timed(BackupStage.Thumbnails) {
            runCatching { MotionPhoto.extractClip(primary.path, fileOps) }
                .onFailure { Logger.w(tag = TAG, throwable = it) { "couldn't read the clip of ${primary.path}" } }
                .getOrNull()
        } ?: return null
        return Clip(path, "video/mp4", part = index, paired = false)
    }

    private suspend fun build(
        row: DeviceBackupAsset,
        uniqueId: Uuid,
        exported: ExportedAsset,
        digests: List<ByteArray>,
        clip: Clip?,
        temps: MutableList<String>,
        send: suspend (MediaUploadSpec) -> HandOffResult,
    ): HandOffResult {
        val primaryIndex = exported.primaryIndex()
        val primary = exported.parts[primaryIndex]
        val quality = settings().photoQuality
        val bundle = timed(BackupStage.Thumbnails) {
            UserUploadBuilder.build(
                listOfNotNull(
                    UploadSource(primary.path, primary.contentType, pickedName = exported.fileName, libraryKind = LibraryKind.IMAGE),
                    clip?.let { UploadSource(it.path, it.contentType, libraryKind = LibraryKind.VIDEO, untouched = it.untouched, poster = false) },
                ),
                // The clip follows the photo setting. At Original an iPhone's paired video is kept with a copy to play;
                // an Android clip is already kept, inside the untouched file.
                options().copy(
                    videoQuality = quality.toVideoQuality(),
                    originalVideo = clip?.paired == true && !clip.untouched && quality == MediaQuality.ORIGINAL,
                ),
                fileOps,
            ) { if (it == 0) PhotobaseProtocol.ImagePayloadKey else PhotobaseProtocol.LivePhotoVideoPayloadKey }
        }
        val expected = mutableMapOf<String, ByteArray>()
        val still = bundle.payloads.single { it.key == PhotobaseProtocol.ImagePayloadKey }
        if (still.filePath == primary.path) {
            expected[still.key] = digests[primaryIndex]
        } else {
            temps += still.filePath
            if (!hasher.matches(exported, primary, digests[primaryIndex])) return HandOffResult.ChangedUnderUs
        }
        // An Android clip comes from the primary, checked above or as it's encrypted.
        if (clip?.paired == true) {
            val live = bundle.payloads.single { it.key == PhotobaseProtocol.LivePhotoVideoPayloadKey }
            if (live.skipVideoProcessing) {
                expected[live.key] = digests[clip.part]
            } else if (!hasher.matches(exported, exported.parts[clip.part], digests[clip.part])) {
                return HandOffResult.ChangedUnderUs
            }
        }
        val exif = timed(BackupStage.Thumbnails) { metadataReader.image(primary.path) }
        val metadata = photoMetadata(
            uniqueId,
            if (clip != null) PhotobaseProtocol.LivePhotoDataType else PhotobaseProtocol.ImageDataType,
            captureTimeMs(exif, row.createdAt),
            PhotoContent(latitude = exif?.latitude, longitude = exif?.longitude, altitude = exif?.altitudeMeters),
            bundle.previewThumbs.firstOrNull(),
        )
        return send(photoSpec(photosDriveId, uniqueId, bundle, metadata, ContentHashes(PayloadHashAlgorithm.Blake3, expected)))
    }

    private fun ExportedAsset.primaryIndex() = parts.indexOfFirst { it.role == ExportedPartRole.Primary }.also { require(it >= 0) { "no primary part" } }

    private companion object {
        const val TAG = "BackupRequestBuilder"
        val MOTION_PHOTO_TYPES = setOf("image/jpeg", "image/heic", "image/heif")
    }
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
