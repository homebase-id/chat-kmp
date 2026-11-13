package id.homebase.chat.services.builder

import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.image.MediaQuality
import id.homebase.core.pdf.generatePdfThumbnailFromFile
import id.homebase.upload.PayloadBundle
import id.homebase.upload.UploadOptions
import id.homebase.upload.UploadSource
import id.homebase.upload.UserUploadBuilder

object MessageAttachmentBuilder {

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
        payloadKeyFactory: (index: Int, attachment: AttachmentInput) -> String
    ): PayloadBundle =
        UserUploadBuilder.build(
            sources = attachments.map { it.toUploadSource() },
            options = UploadOptions(quality = mediaQuality),
            fileOps = fileOperationsProvider,
            pdfPageRenderer = { path, width -> generatePdfThumbnailFromFile(path, width)?.thumbnailBytes },
        ) { index -> payloadKeyFactory(index, attachments[index]) }
}

private fun AttachmentInput.toUploadSource() = UploadSource(
    path = filePath,
    contentType = contentType,
    pickedName = displayName,
    sticker = forceSticker,
    waveformPath = waveformFile,
    audioLengthSeconds = audioLengthSeconds,
    trimStartMs = trimStartMs,
    trimEndMs = trimEndMs,
    inputBlobUrl = inputBlobUrl,
)
