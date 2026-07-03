package id.homebase.chat.services.builder

import id.homebase.api.client.drives.files.DescriptorContent
import id.homebase.api.client.drives.files.PayloadFile
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.lib.image.ImageFormatDetector
import id.homebase.upload.PayloadBundle

object MessageAttachmentBuilder {

    suspend fun buildSingle(
        attachment: AttachmentInput,
        fileOperationsProvider: FileOperationsProvider,
        payloadKey: String
    ): PayloadBundle {
        return build(
            attachments = listOf(attachment),
            fileOperationsProvider = fileOperationsProvider
        ) { _, _ -> payloadKey }
    }

    suspend fun build(
        attachments: List<AttachmentInput>,
        fileOperationsProvider: FileOperationsProvider,
        payloadKeyFactory: (index: Int, attachment: AttachmentInput) -> String
    ): PayloadBundle {
        val bundles =
            attachments.mapIndexed { index, attachment ->

                val payloadKey = payloadKeyFactory(index, attachment)

                when {
                    attachment.contentType.startsWith("image/") -> {
                        val thumbs =
                            MessageThumbnailGenerator.generate(
                                attachment.filePath,
                                payloadKey,
                                fileOperationsProvider,
                            )

                        // Sticker is opt-in only: the "Send as sticker" toggle, the sticker
                        // tool, and the background-remover all set forceSticker=true. We do
                        // NOT auto-sticker by transparency — a shared/normal image (or any
                        // transparent PNG/WebP the user didn't choose to stickerize) must send
                        // as a normal image (issue #854). When it IS a sticker we detect the
                        // real format so the download is named "StickerFile.<ext>"; ordinary
                        // photos keep the legacy "" so nothing changes for them.
                        val descriptorContent =
                            if (attachment.forceSticker) {
                                DescriptorContent.descriptorContentFromImage(
                                    isSticker = true,
                                    format = ImageFormatDetector.detectFormat(thumbs.sourceBytes),
                                )
                            } else {
                                ""
                            }

                        PayloadBundle(
                            payloads =
                                listOf(
                                    PayloadFile(
                                        key = payloadKey,
                                        filePath = attachment.filePath,
                                        contentType = attachment.contentType,
                                        previewThumbnail = thumbs.preview,
                                        descriptorContent = descriptorContent
                                    )
                                ),
                            thumbnails = thumbs.thumbnails,
                            previewThumbs = listOfNotNull(thumbs.preview)
                        )
                    }
                    attachment.contentType.startsWith("audio/") -> {
                        val thumbs = if (attachment.waveformFile == null) null else
                            MessageThumbnailGenerator.generate(
                                attachment.waveformFile,
                                payloadKey,
                                fileOperationsProvider,
                            )

                        PayloadBundle(
                            payloads =
                                listOf(
                                    PayloadFile(
                                        key = payloadKey,
                                        filePath = attachment.filePath,
                                        contentType = attachment.contentType,
                                        previewThumbnail = thumbs?.preview,
                                        descriptorContent = DescriptorContent.descriptorContentFromAudioFile(
                                            name = attachment.displayName ?: attachment.filePath,
                                            lengthSeconds = attachment.audioLengthSeconds ?: 0)
                                    )
                                ),
                            thumbnails = thumbs?.thumbnails ?: emptyList(),
                            previewThumbs = listOfNotNull(thumbs?.preview)
                        )
                    }
                    attachment.contentType == "application/pdf" -> {
                        val thumbs =
                            MessageThumbnailGenerator.generateFromPdf(attachment.filePath, payloadKey)

                        PayloadBundle(
                            payloads =
                                listOf(
                                    PayloadFile(
                                        key = payloadKey,
                                        filePath = attachment.filePath,
                                        contentType = attachment.contentType,
                                        previewThumbnail = thumbs?.preview,
                                        descriptorContent = attachment.displayName,
                                    )
                                ),
                            thumbnails = thumbs?.thumbnails ?: emptyList(),
                            previewThumbs = listOfNotNull(thumbs?.preview)
                        )
                    }
                    else ->
                        PayloadBundle(
                            payloads =
                                listOf(
                                    PayloadFile(
                                        key = payloadKey,
                                        filePath = attachment.filePath,
                                        contentType = attachment.contentType,
                                        descriptorContent = attachment.displayName,
                                        trimStartMs = attachment.trimStartMs,
                                        trimEndMs = attachment.trimEndMs,
                                        inputBlobUrl = attachment.inputBlobUrl,
                                    )
                                ),
                            thumbnails = emptyList(),
                            previewThumbs = emptyList()
                        )
                }
            }

        return PayloadBundle(
            payloads = bundles.flatMap { it.payloads },
            thumbnails = bundles.flatMap { it.thumbnails },
            previewThumbs = bundles.flatMap { it.previewThumbs }
        )
    }
}