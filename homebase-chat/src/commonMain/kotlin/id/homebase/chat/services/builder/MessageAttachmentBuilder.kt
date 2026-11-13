package id.homebase.chat.services.builder

import id.homebase.api.client.drives.files.DescriptorContentV1
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.image.MediaQuality
import id.homebase.core.pdf.generatePdfThumbnailFromFile
import id.homebase.upload.MetadataPolicy
import id.homebase.upload.PayloadBundle
import id.homebase.upload.UploadOptions
import id.homebase.upload.UploadSource
import id.homebase.upload.UserUploadBuilder

object MessageAttachmentBuilder {

    private const val PDF_PREVIEW_RENDER_WIDTH = 1600

    suspend fun buildSingle(
        attachment: AttachmentInput,
        fileOperationsProvider: FileOperationsProvider,
        payloadKey: String,
        mediaQuality: MediaQuality = MediaQuality.STANDARD,
    ): PayloadBundle {
        return build(
            attachments = listOf(attachment),
            fileOperationsProvider = fileOperationsProvider,
            mediaQuality = mediaQuality,
        ) { _, _ -> payloadKey }
    }

    /**
     * [mediaQuality] defaults to STANDARD so callers that send incidental images — group avatars,
     * event covers — get the downscale without threading the preference. Only the four user-media
     * send paths read the real setting.
     */
    suspend fun build(
        attachments: List<AttachmentInput>,
        fileOperationsProvider: FileOperationsProvider,
        mediaQuality: MediaQuality = MediaQuality.STANDARD,
        currentFormat: Boolean = DescriptorContentV1.WRITE,
        payloadKeyFactory: (index: Int, attachment: AttachmentInput) -> String
    ): PayloadBundle =
        UserUploadBuilder.build(
            sources = attachments.map { it.toUploadSource() },
            options = UploadOptions(quality = mediaQuality, metadata = MetadataPolicy.STRIP, currentFormat = currentFormat),
            fileOps = fileOperationsProvider,
            pdfPageRenderer = { path -> generatePdfThumbnailFromFile(path, PDF_PREVIEW_RENDER_WIDTH)?.thumbnailBytes },
        ) { index, _ -> payloadKeyFactory(index, attachments[index]) }
}

private fun AttachmentInput.toUploadSource() = UploadSource(
    path = filePath,
    contentType = contentType,
    pickedName = displayName,
    kindHint = DescriptorContentV1.Kind.STICKER.takeIf { forceSticker },
    waveformPath = waveformFile,
    audioLengthSeconds = audioLengthSeconds,
    trimStartMs = trimStartMs,
    trimEndMs = trimEndMs,
    inputBlobUrl = inputBlobUrl,
)
