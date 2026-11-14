package id.homebase.upload

import co.touchlab.kermit.Logger
import id.homebase.api.client.drives.files.DescriptorContent
import id.homebase.api.client.drives.files.DescriptorContentSeal
import id.homebase.api.client.drives.files.DescriptorContentV1
import id.homebase.api.client.drives.files.PayloadFile
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.image.ImageMetadata
import id.homebase.api.image.ImageSize
import id.homebase.api.image.MediaQuality
import id.homebase.api.lib.image.ImageFormatDetector
import id.homebase.api.util.codePointCount
import id.homebase.api.util.safeFileName
import id.homebase.api.util.truncateToCodePoints
import id.homebase.api.video.VideoQuality
import id.homebase.api.video.toVideoQuality
import kotlinx.coroutines.CancellationException

private const val TAG = "UserUploadBuilder"
private const val OCTET_STREAM = "application/octet-stream"

enum class MetadataPolicy { KEEP, STRIP }

/** What a photo library already knows a file is, whatever type it arrives with. */
enum class LibraryKind { IMAGE, VIDEO }

/** One file a user uploads, as the app has it (UPLOADS.md section 4). */
data class UploadSource(
    val path: String,
    /** The platform's type, else the name's extension; an image's own bytes override it. */
    val contentType: String,
    val pickedName: String? = null,
    /** The user's choice, never inferred. */
    val sticker: Boolean = false,
    val libraryKind: LibraryKind? = null,
    /** Video: nothing will transcode it, so what is sent is the user's own file. */
    val untouched: Boolean = false,
    val waveformPath: String? = null,
    val audioLengthSeconds: Int? = null,
    val trimStartMs: Long? = null,
    val trimEndMs: Long? = null,
    /** Web, video: the picked file's `blob:` URL, so ffmpeg reads it in JS. */
    val inputBlobUrl: String? = null,
    /** Files only: what the app keeps on the payload for itself, such as a note's preview (`x`). */
    val appContent: String? = null,
)

data class UploadOptions(
    val quality: MediaQuality = MediaQuality.STANDARD,
    val metadata: MetadataPolicy = MetadataPolicy.STRIP,
    val currentFormat: Boolean = DescriptorContentV1.WRITE,
    /** Until an original video can be sent untouched, an app may keep its own transcode tier. */
    val videoQuality: VideoQuality = quality.toVideoQuality(),
)

/** Renders a PDF's first page, [width] px wide, for its thumbnails; the renderer lives above this module. */
typealias PdfPageRenderer = suspend (path: String, width: Int) -> ByteArray?

/**
 * The payloads, thumbnails and descriptor content of everything a user uploads, the same in every app
 * (UPLOADS.md). The transport applies the metadata strip it decides, encrypts, and seals.
 */
object UserUploadBuilder {

    private class Built(val payload: PayloadFile, val thumbs: ThumbnailResult?)

    /** An app's own data on a payload: the first of [tries] that fits; null when none does, and the receiver shows a plain link or pin. */
    fun appDescriptor(vararg tries: String, currentFormat: Boolean = DescriptorContentV1.WRITE): String? =
        tries.asSequence()
            .map { if (currentFormat) DescriptorContentV1(kind = DescriptorContentV1.Kind.APP, appContent = it).encode() else it }
            .firstOrNull { DescriptorContentSeal.fits(it, sealed = currentFormat) }

    suspend fun build(
        sources: List<UploadSource>,
        options: UploadOptions,
        fileOps: FileOperationsProvider,
        pdfPageRenderer: PdfPageRenderer? = null,
        /** Given each image's EXIF, read in the decode that makes its thumbnails; passing it is what asks for the read. */
        onImageMetadata: ((payloadKey: String, metadata: ImageMetadata) -> Unit)? = null,
        payloadKey: (index: Int) -> String,
    ): PayloadBundle {
        val built = sources.mapIndexed { index, source ->
            buildOne(source, payloadKey(index), options, fileOps, pdfPageRenderer, readImageMetadata = onImageMetadata != null)
        }
        if (onImageMetadata != null) built.forEach { b -> b.thumbs?.metadata?.let { onImageMetadata(b.payload.key, it) } }
        return PayloadBundle(
            payloads = built.map { it.payload.copy(previewThumbnail = it.thumbs?.preview) },
            thumbnails = built.flatMap { it.thumbs?.thumbnails.orEmpty() },
            previewThumbs = built.mapNotNull { it.thumbs?.preview },
        )
    }

    private suspend fun buildOne(
        source: UploadSource,
        key: String,
        options: UploadOptions,
        fileOps: FileOperationsProvider,
        pdfPageRenderer: PdfPageRenderer?,
        readImageMetadata: Boolean,
    ): Built {
        val knownImage = source.sticker || source.libraryKind == LibraryKind.IMAGE
        require(source.appContent == null || !(source.contentType.startsWith("image/") || source.contentType.startsWith("audio/") || knownImage)) {
            "appContent is for files; an image or audio payload would drop it"
        }
        // The bytes decide the type with the current format only: the detector's weaker signatures ("BM", AVIF's
        // mif1 brand) must be tightened before the switch flips (UPLOADS.md section 7).
        val untyped = source.contentType.isBlank() || source.contentType == OCTET_STREAM
        val contentType = if (options.currentFormat && untyped && source.libraryKind == null) sniffed(source.path, fileOps) else source.contentType
        val name = source.pickedName?.takeIf { it.isNotBlank() }?.let { safeFileName(it) }
        val oldName = source.pickedName?.let { safeFileName(it) }
        fun descriptor(current: () -> DescriptorContentV1, old: () -> String?) =
            if (options.currentFormat) current().encode() else old()

        return when {
            // A sticker or a library photo is an image whatever type it arrives with.
            contentType.startsWith("image/") || knownImage -> image(source, contentType, key, name, options, fileOps, readImageMetadata)
            contentType.startsWith("audio/") -> Built(
                PayloadFile(
                    key = key,
                    filePath = source.path,
                    contentType = contentType,
                    descriptorContent = descriptor(
                        { DescriptorContentV1(kind = DescriptorContentV1.Kind.AUDIO, name = name, lengthSeconds = source.audioLengthSeconds) },
                        // Nothing to record for an unnamed, unmeasured file: Vault keeps names in its own content.
                        { if (source.pickedName == null && source.audioLengthSeconds == null) null else DescriptorContent.descriptorContentFromAudioFile(safeFileName(source.pickedName ?: source.path), source.audioLengthSeconds ?: 0) },
                    ),
                ),
                source.waveformPath?.let { UploadImages.generate(it, key, fileOps) },
            )
            else -> {
                val kind = when {
                    source.appContent != null -> DescriptorContentV1.Kind.APP
                    contentType.startsWith("video/") || source.libraryKind == LibraryKind.VIDEO -> DescriptorContentV1.Kind.VIDEO
                    else -> DescriptorContentV1.Kind.FILE
                }
                val original = 1.takeIf { source.untouched && kind == DescriptorContentV1.Kind.VIDEO }
                Built(
                    PayloadFile(
                        key = key,
                        filePath = source.path,
                        contentType = contentType,
                        descriptorContent = descriptor(
                            { DescriptorContentV1(kind = kind, name = name, original = original).withFittingAppContent(source.appContent) },
                            { source.appContent ?: oldName },
                        ),
                        trimStartMs = source.trimStartMs,
                        trimEndMs = source.trimEndMs,
                        inputBlobUrl = source.inputBlobUrl,
                        videoQuality = options.videoQuality,
                    ),
                    pdfPageRenderer?.takeIf { contentType == "application/pdf" }?.let { UploadImages.fromPdf(source.path, key, it) },
                )
            }
        }
    }

    private suspend fun image(
        source: UploadSource,
        declaredType: String,
        key: String,
        name: String?,
        options: UploadOptions,
        fileOps: FileOperationsProvider,
        readImageMetadata: Boolean,
    ): Built {
        val thumbs = UploadImages.generate(source.path, key, fileOps, options.quality, forceSticker = source.sticker, readMetadata = readImageMetadata)
        val finalPath = UploadImages.finalImagePath(thumbs, fileOps)
        // A library image skipped the read up front: the decode names its type, and only a failed decode needs the header.
        val untypedLibraryImage = source.libraryKind == LibraryKind.IMAGE && (declaredType.isBlank() || declaredType == OCTET_STREAM)
        val bytesType = if (!options.currentFormat) null else thumbs.sourceFormat ?: if (untypedLibraryImage) sniffed(source.path, fileOps) else null
        val sniffedType = bytesType?.takeIf { it.startsWith("image/") }
        val contentType = if (finalPath != null) "image/webp" else sniffedType ?: declaredType

        // Quality is recorded only for an HD or Original photo that really is one, and never for a
        // sticker: its bytes are bounded at 512 px whatever the setting.
        val sentFinalImage = thumbs.finalImage?.takeIf { finalPath != null }
        val finalSize = sentFinalImage?.let { ImageSize(it.width, it.height) } ?: thumbs.displayedSize
        val recordedQuality = options.quality.takeIf {
            it == MediaQuality.ORIGINAL || (it == MediaQuality.HD && thumbs.finalImage != null)
        }
        val descriptorContent = when {
            options.currentFormat -> DescriptorContentV1(
                kind = if (source.sticker) DescriptorContentV1.Kind.STICKER else DescriptorContentV1.Kind.IMAGE,
                name = name.takeIf { !source.sticker },
                original = if (finalPath == null) 1 else 0,
                pixelWidth = finalSize?.pixelWidth,
                pixelHeight = finalSize?.pixelHeight,
                quality = when {
                    source.sticker -> null
                    recordedQuality != null -> DescriptorContentV1.Quality.of(recordedQuality)
                    sentFinalImage != null -> DescriptorContentV1.Quality.SD
                    else -> null
                },
            ).encode()
            source.sticker -> DescriptorContent.descriptorContentFromImage(
                isSticker = true,
                format = thumbs.sourceFormat ?: ImageFormatDetector.detectFormat(fileOps.readFileHeaderBytes(source.path)),
            )
            recordedQuality != null || finalSize != null -> DescriptorContent.descriptorContentFromImage(
                isSticker = false,
                quality = recordedQuality,
                pixelWidth = finalSize?.pixelWidth,
                pixelHeight = finalSize?.pixelHeight,
            )
            else -> ""
        }

        return Built(
            PayloadFile(
                key = key,
                filePath = finalPath ?: source.path,
                contentType = contentType,
                descriptorContent = descriptorContent,
                stripMetadata = options.metadata == MetadataPolicy.STRIP,
            ),
            thumbs,
        )
    }

    // A file's app content is plain text (a note's preview), so it is cut a character at a time until it fits once sealed.
    private fun DescriptorContentV1.withFittingAppContent(text: String?): DescriptorContentV1 {
        var fitting = text ?: return this
        while (fitting.isNotEmpty() && !DescriptorContentSeal.fits(copy(appContent = fitting).encode(), sealed = true)) {
            fitting = fitting.truncateToCodePoints(fitting.codePointCount() - 1)
        }
        return copy(appContent = fitting.ifEmpty { null })
    }

    // Only an untyped file is read for its type up front: it decides which branch the file takes.
    private suspend fun sniffed(path: String, fileOps: FileOperationsProvider): String {
        val header = try {
            fileOps.readFileHeaderBytes(path)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(tag = TAG, throwable = e) { "Couldn't read $path to check its type" }
            return OCTET_STREAM
        }
        return ImageFormatDetector.detectFormat(header)
    }
}
