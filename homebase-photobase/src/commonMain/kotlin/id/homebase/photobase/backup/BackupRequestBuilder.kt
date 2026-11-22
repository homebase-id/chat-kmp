package id.homebase.photobase.backup

import id.homebase.api.video.toVideoQuality
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.AccessControlList
import id.homebase.api.client.drives.files.PayloadHashAlgorithm
import id.homebase.api.client.drives.files.SecurityGroupType
import id.homebase.api.client.drives.upload.EmbeddedThumb
import id.homebase.api.client.drives.upload.UploadAppFileMetaData
import id.homebase.api.client.drives.upload.UploadFileMetadata
import id.homebase.api.file.CONTENT_TYPE_MP4
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
     * A Live or motion photo's clip goes as `photo_live` (UPLOADS.md section 3.7), its transcode run through [transcoding].
     */
    suspend fun build(
        row: DeviceBackupAsset,
        uniqueId: Uuid,
        exported: ExportedAsset,
        transcoding: TranscodeGuard = { it() },
        send: suspend (MediaUploadSpec) -> HandOffResult,
    ): HandOffResult {
        val digests = digestsOf(row)
        require(exported.parts.size == digests.size) { "${exported.parts.size} parts against ${digests.size} digests" }
        val primaryIndex = exported.parts.indexOfFirst { it.role == ExportedPartRole.Primary }.also { require(it >= 0) { "no primary part" } }
        val primary = exported.parts[primaryIndex]
        val quality = settings().photoQuality
        val temps = mutableListOf<String>()

        suspend fun attempt(clip: Clip?): HandOffResult {
            val bundle = timed(BackupStage.Thumbnails) {
                UserUploadBuilder.build(
                    listOfNotNull(
                        UploadSource(primary.path, primary.contentType, pickedName = exported.fileName, libraryKind = LibraryKind.IMAGE),
                        clip?.let { UploadSource(it.path, it.contentType, libraryKind = LibraryKind.VIDEO, untouched = it.untouched, photoClip = true) },
                    ),
                    // An Android clip needs no copy kept: the untouched file holds it.
                    options().copy(
                        videoQuality = quality.toVideoQuality(),
                        originalVideo = clip?.pairedPart != null && !clip.untouched && quality == MediaQuality.ORIGINAL,
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
            clip?.pairedPart?.let { part ->
                val live = bundle.payloads.single { it.key == PhotobaseProtocol.LivePhotoVideoPayloadKey }
                if (live.skipVideoProcessing) {
                    expected[live.key] = digests[part]
                } else if (!hasher.matches(exported, exported.parts[part], digests[part])) {
                    return HandOffResult.ChangedUnderUs
                }
            }
            val exif = timed(BackupStage.Thumbnails) { metadataReader.image(primary.path) }
            fun spec(payloads: PayloadBundle, dataType: Int) = photoSpec(
                photosDriveId,
                uniqueId,
                payloads,
                photoMetadata(
                    uniqueId,
                    dataType,
                    captureTimeMs(exif, row.createdAt),
                    PhotoContent(latitude = exif?.latitude, longitude = exif?.longitude, altitude = exif?.altitudeMeters),
                    payloads.previewThumbs.firstOrNull(),
                ),
                ContentHashes(PayloadHashAlgorithm.Blake3, expected.filterKeys { key -> payloads.payloads.any { it.key == key } }),
            )
            if (clip == null) return send(spec(bundle, PhotobaseProtocol.ImageDataType))
            if (clip.untouched) return send(spec(bundle, PhotobaseProtocol.LivePhotoDataType))
            // A backup that isn't the original isn't a backup: at Original an iPhone's paired video stays, as it is.
            suspend fun withNoClipToPlay(): HandOffResult =
                if (clip.pairedPart != null && quality == MediaQuality.ORIGINAL) attempt(clip.copy(untouched = true))
                else send(spec(bundle.withOnly(PhotobaseProtocol.ImagePayloadKey), PhotobaseProtocol.ImageDataType))
            return try {
                transcoding { send(spec(bundle, PhotobaseProtocol.LivePhotoDataType)) } ?: run {
                    Logger.w(tag = TAG) { "the clip of $uniqueId stopped the app before; backing up without it" }
                    withNoClipToPlay()
                }
            } catch (e: VideoCompressionFailedException) {
                // Retried like any failure, so only a clip that never transcodes costs the motion; the still is kept.
                if (!(clip.pairedPart != null && quality == MediaQuality.ORIGINAL) && row.attempts + 1 < BackupLedger.MAX_ATTEMPTS) throw e
                Logger.w(tag = TAG, throwable = e) { "no clip to play for $uniqueId after ${row.attempts + 1} attempts" }
                withNoClipToPlay()
            }
        }

        try {
            val paired = exported.parts.withIndex().firstOrNull { it.value.role == ExportedPartRole.PairedVideo }
            val clip = paired?.let { (index, part) -> Clip(part.path, part.contentType, pairedPart = index) }
                ?: cutClip(primary)?.also { temps += it.path }
            return attempt(clip)
        } finally {
            temps.forEach { runCatching { fileOps.deleteTempFile(it) } }
        }
    }

    /** [pairedPart]: the exported part an iPhone's clip is; null for a clip cut from the file. [untouched]: no copy to play. */
    private data class Clip(val path: String, val contentType: String, val pairedPart: Int? = null, val untouched: Boolean = false)

    // Only JPEG motion photos so far; a HEIC one's XMP can lie past the header (THUMBNAILS.md, "Not settled").
    private suspend fun cutClip(primary: ExportedPart): Clip? {
        if (primary.contentType != "image/jpeg") return null
        val path = timed(BackupStage.Thumbnails) { MotionPhoto.extractClip(primary.path, fileOps) } ?: return null
        return Clip(path, CONTENT_TYPE_MP4)
    }

    private fun PayloadBundle.withOnly(key: String) =
        copy(payloads = payloads.filter { it.key == key }, thumbnails = thumbnails.filter { it.key == key })

    private companion object {
        const val TAG = "BackupRequestBuilder"
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
