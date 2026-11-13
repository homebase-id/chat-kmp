package id.homebase.upload

import co.touchlab.kermit.Logger
import id.homebase.api.client.drives.files.DescriptorContent
import id.homebase.api.client.drives.files.DescriptorContentV1
import id.homebase.api.client.drives.files.PayloadFile
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.image.ImageSize
import id.homebase.api.image.MediaQuality
import id.homebase.api.lib.image.ImageFormatDetector
import id.homebase.api.util.safeFileName
import id.homebase.api.video.toVideoQuality
import kotlinx.coroutines.CancellationException

private const val TAG = "UserUploadBuilder"

enum class MetadataPolicy { KEEP, STRIP }

/** One file a user uploads, as the app has it (UPLOADS.md section 4). */
data class UploadSource(
    val path: String,
    /** The platform's type, else the name's extension; an image's own bytes override it. */
    val contentType: String,
    val pickedName: String? = null,
    /** Only [DescriptorContentV1.Kind.STICKER] today: a sticker is the user's choice, never inferred. */
    val kindHint: DescriptorContentV1.Kind? = null,
    val waveformPath: String? = null,
    val audioLengthSeconds: Int? = null,
    val trimStartMs: Long? = null,
    val trimEndMs: Long? = null,
    /** Web, video: the picked file's `blob:` URL, so ffmpeg reads it in JS. */
    val inputBlobUrl: String? = null,
)

data class UploadOptions(
    val quality: MediaQuality = MediaQuality.STANDARD,
    val metadata: MetadataPolicy = MetadataPolicy.STRIP,
    val currentFormat: Boolean = DescriptorContentV1.WRITE,
)

/** Renders a PDF's first page for its thumbnails; the renderer lives above this module. */
typealias PdfPageRenderer = suspend (path: String) -> ByteArray?

/**
 * Builds the payloads, thumbnails and descriptor content of everything a user uploads, the same way in
 * every app (UPLOADS.md). Payload encryption, and sealing the descriptor content of an encrypted file,
 * stay in [UploadService].
 */
object UserUploadBuilder {

    suspend fun build(
        sources: List<UploadSource>,
        options: UploadOptions,
        fileOps: FileOperationsProvider,
        pdfPageRenderer: PdfPageRenderer? = null,
        payloadKey: (index: Int, source: UploadSource) -> String,
    ): PayloadBundle {
        val bundles = sources.mapIndexed { index, source ->
            val typed = source.copy(contentType = contentTypeOf(source, fileOps))
            buildOne(typed, payloadKey(index, typed), options, fileOps, pdfPageRenderer)
        }
        return PayloadBundle(
            payloads = bundles.flatMap { it.payloads },
            thumbnails = bundles.flatMap { it.thumbnails },
            previewThumbs = bundles.flatMap { it.previewThumbs },
        )
    }

    private suspend fun buildOne(
        source: UploadSource,
        key: String,
        options: UploadOptions,
        fileOps: FileOperationsProvider,
        pdfPageRenderer: PdfPageRenderer?,
    ): PayloadBundle {
        val name = source.pickedName?.takeIf { it.isNotBlank() }?.let { safeFileName(it) }
        return when {
            source.contentType.startsWith("image/") -> image(source, key, name, options, fileOps)
            source.contentType.startsWith("audio/") -> audio(source, key, name, options, fileOps)
            source.contentType == "application/pdf" -> pdf(source, key, name, options, pdfPageRenderer)
            else -> PayloadBundle(
                payloads = listOf(
                    PayloadFile(
                        key = key,
                        filePath = source.path,
                        contentType = source.contentType,
                        descriptorContent = if (!options.currentFormat) name else DescriptorContentV1(
                            kind = if (source.contentType.startsWith("video/")) DescriptorContentV1.Kind.VIDEO else DescriptorContentV1.Kind.FILE,
                            name = name,
                        ).encode(),
                        trimStartMs = source.trimStartMs,
                        trimEndMs = source.trimEndMs,
                        inputBlobUrl = source.inputBlobUrl,
                        videoQuality = options.quality.toVideoQuality(),
                    ),
                ),
                thumbnails = emptyList(),
                previewThumbs = emptyList(),
            )
        }
    }

    private suspend fun image(
        source: UploadSource,
        key: String,
        name: String?,
        options: UploadOptions,
        fileOps: FileOperationsProvider,
    ): PayloadBundle {
        val sticker = source.kindHint == DescriptorContentV1.Kind.STICKER
        val thumbs = UploadImages.generate(source.path, key, fileOps, options.quality, forceSticker = sticker)
        val (finalPath, finalType) = UploadImages.finalImageOrSource(source.path, source.contentType, thumbs, fileOps)
        val keptSource = finalPath == source.path
        val payloadPath = if (keptSource && options.metadata == MetadataPolicy.STRIP) {
            withoutImageMetadata(source.path, source.contentType, fileOps)
        } else {
            finalPath
        }

        // Quality is recorded only for an HD or Original photo that really is one, and never for a
        // sticker: its bytes are bounded at 512 px whatever the setting.
        val sentFinalImage = thumbs.finalImage?.takeIf { !keptSource }
        val finalSize = sentFinalImage?.let { ImageSize(it.width, it.height) } ?: thumbs.displayedSize
        val recordedQuality = options.quality.takeIf {
            it == MediaQuality.ORIGINAL || (it == MediaQuality.HD && thumbs.finalImage != null)
        }
        val descriptorContent = when {
            options.currentFormat -> DescriptorContentV1(
                kind = if (sticker) DescriptorContentV1.Kind.STICKER else DescriptorContentV1.Kind.IMAGE,
                name = name.takeIf { !sticker },
                original = if (keptSource) 1 else 0,
                pixelWidth = finalSize?.pixelWidth,
                pixelHeight = finalSize?.pixelHeight,
                quality = when {
                    sticker -> null
                    recordedQuality != null -> DescriptorContentV1.Quality.of(recordedQuality)
                    sentFinalImage != null -> DescriptorContentV1.Quality.SD
                    else -> null
                },
            ).encode()
            sticker -> DescriptorContent.descriptorContentFromImage(isSticker = true, format = thumbs.sourceFormat)
            recordedQuality != null || finalSize != null -> DescriptorContent.descriptorContentFromImage(
                isSticker = false,
                quality = recordedQuality,
                pixelWidth = finalSize?.pixelWidth,
                pixelHeight = finalSize?.pixelHeight,
            )
            else -> ""
        }

        return PayloadBundle(
            payloads = listOf(
                PayloadFile(
                    key = key,
                    filePath = payloadPath,
                    contentType = finalType,
                    previewThumbnail = thumbs.preview,
                    descriptorContent = descriptorContent,
                ),
            ),
            thumbnails = thumbs.thumbnails,
            previewThumbs = listOfNotNull(thumbs.preview),
        )
    }

    private suspend fun audio(
        source: UploadSource,
        key: String,
        name: String?,
        options: UploadOptions,
        fileOps: FileOperationsProvider,
    ): PayloadBundle {
        val thumbs = source.waveformPath?.let { UploadImages.generate(it, key, fileOps) }
        val descriptorContent = if (options.currentFormat) {
            DescriptorContentV1(kind = DescriptorContentV1.Kind.AUDIO, name = name, lengthSeconds = source.audioLengthSeconds).encode()
        } else {
            DescriptorContent.descriptorContentFromAudioFile(
                name = name ?: safeFileName(source.path),
                lengthSeconds = source.audioLengthSeconds ?: 0,
            )
        }
        return PayloadBundle(
            payloads = listOf(
                PayloadFile(
                    key = key,
                    filePath = source.path,
                    contentType = source.contentType,
                    previewThumbnail = thumbs?.preview,
                    descriptorContent = descriptorContent,
                ),
            ),
            thumbnails = thumbs?.thumbnails ?: emptyList(),
            previewThumbs = listOfNotNull(thumbs?.preview),
        )
    }

    private suspend fun pdf(
        source: UploadSource,
        key: String,
        name: String?,
        options: UploadOptions,
        pdfPageRenderer: PdfPageRenderer?,
    ): PayloadBundle {
        val thumbs = pdfPageRenderer?.let { UploadImages.fromPdf(source.path, key, it) }
        return PayloadBundle(
            payloads = listOf(
                PayloadFile(
                    key = key,
                    filePath = source.path,
                    contentType = source.contentType,
                    previewThumbnail = thumbs?.preview,
                    descriptorContent = if (options.currentFormat) {
                        DescriptorContentV1(kind = DescriptorContentV1.Kind.FILE, name = name).encode()
                    } else {
                        name
                    },
                ),
            ),
            thumbnails = thumbs?.thumbnails ?: emptyList(),
            previewThumbs = listOfNotNull(thumbs?.preview),
        )
    }

    // The bytes outrank what the platform says, for the formats the detector knows (UPLOADS.md 3.1).
    // Only images and untyped files are sniffed: other types can't be images, and their sources may not be readable here.
    private suspend fun contentTypeOf(source: UploadSource, fileOps: FileOperationsProvider): String {
        val declared = source.contentType.ifBlank { OCTET_STREAM }
        if (!declared.startsWith("image/") && declared != OCTET_STREAM) return declared
        val header = try {
            fileOps.readFileHeaderBytes(source.path)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(tag = TAG, throwable = e) { "Couldn't read ${source.path} to check its type; keeping $declared" }
            return declared
        }
        return ImageFormatDetector.detectFormat(header).takeIf { it != OCTET_STREAM } ?: declared
    }

    private const val OCTET_STREAM = "application/octet-stream"
}
