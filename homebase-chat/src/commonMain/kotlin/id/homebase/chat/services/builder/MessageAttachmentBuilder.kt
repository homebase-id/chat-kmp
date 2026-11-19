package id.homebase.chat.services.builder

import id.homebase.upload.MetadataPolicy
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.image.SendQuality
import id.homebase.api.video.toVideoQuality
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
        quality: SendQuality = SendQuality.Standard,
    ): PayloadBundle {
        return build(
            attachments = listOf(attachment),
            fileOperationsProvider = fileOperationsProvider,
            quality = quality,
        ) { _, _ -> payloadKey }
    }

    /**
     * [quality] defaults to Standard so callers that send incidental images — group avatars,
     * event covers — get the downscale without threading the preference. Only the user-media
     * send paths read an app's setting.
     */
    suspend fun build(
        attachments: List<AttachmentInput>,
        fileOperationsProvider: FileOperationsProvider,
        quality: SendQuality = SendQuality.Standard,
        payloadKeyFactory: (index: Int, attachment: AttachmentInput) -> String
    ): PayloadBundle =
        UserUploadBuilder.build(
            sources = attachments.map { it.toUploadSource() },
            options = UploadOptions(quality = quality.photo, metadata = MetadataPolicy.STRIP, videoQuality = quality.video.toVideoQuality()),
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
