package id.homebase.chat.services.builder

import id.homebase.api.client.drives.files.PayloadFile
import id.homebase.api.file.FileOperationsProvider
import id.homebase.chat.services.ChatProtocol
import id.homebase.chat.services.PayloadBundle

object MessageAttachmentBuilder {
    suspend fun build(
        attachments: List<AttachmentInput>,
        fileOperationsProvider: FileOperationsProvider,
    ): PayloadBundle {
        val bundles =
            attachments.mapIndexed { index, attachment ->
                val payloadKey = "${ChatProtocol.PAYLOAD_KEY_MESSAGE_WEB}$index"

                when {
                    attachment.contentType.startsWith("image/") -> {
                        val thumbs =
                            MessageThumbnailGenerator.generate(
                                attachment.filePath,
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
                                        previewThumbnail = thumbs.preview,
                                        descriptorContent = ""
                                    )
                                ),
                            thumbnails = thumbs.thumbnails,
                            previewThumbs = listOfNotNull(thumbs.preview)
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
                                        descriptorContent = attachment.displayName
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