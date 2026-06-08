package id.homebase.chat.services.builder

import id.homebase.api.client.drives.files.DescriptorContent
import id.homebase.api.client.drives.files.PayloadFile
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.image.ImageUtils
import id.homebase.api.lib.image.ImageFormatDetector
import id.homebase.chat.services.PayloadBundle

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

                        // Sticker auto-detect: a transparent cut-out image renders
                        // without the opaque bubble backdrop. Detect on the ORIGINAL
                        // source bytes (the generator already read them — no second
                        // read), gated to alpha-capable formats (PNG/WebP). forceSticker
                        // (the "Send as sticker" toggle) bypasses detection. We store a tiny
                        // {"isSticker":true,"format":...} descriptor only when transparent;
                        // ordinary photos keep the legacy "" so nothing changes for them.
                        val format = ImageFormatDetector.detectFormat(thumbs.sourceBytes)
                        val isSticker = attachment.forceSticker ||
                            ((format == "image/png" || format == "image/webp") &&
                                ImageUtils.hasNonOpaquePixels(thumbs.sourceBytes))
                        val descriptorContent =
                            if (isSticker) {
                                // Carry the detected format so a downloaded sticker is named
                                // "StickerFile.<ext>" (see PayloadDescriptor.filename()) rather
                                // than the sender's original filename (e.g. IMG_1234.png).
                                DescriptorContent.descriptorContentFromImage(
                                    isSticker = true,
                                    format = format,
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