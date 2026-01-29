package id.homebase.chat.services.builder

import id.homebase.api.client.drives.files.PayloadFile
import id.homebase.chat.services.ChatProtocol
import id.homebase.chat.services.PayloadBundle

object MessageAttachmentBuilder {
    suspend fun build(
        attachments: List<AttachmentInput>
    ): PayloadBundle {
        val bundles =
            attachments.mapIndexed { index, input ->
                val payloadKey = "${ChatProtocol.PAYLOAD_KEY_MESSAGE_WEB}$index"
                val descriptorKey = "$ChatProtocol.DEFAULT_PAYLOAD_DESCRIPTOR_KEY$index";

                when {
                    input.contentType.startsWith("image/") -> {
                        val thumbs =
                            MessageThumbnailGenerator.generate(
                                input.filePath,
                                payloadKey
                            )

                        PayloadBundle(
                            payloads =
                                listOf(
                                    PayloadFile(
                                        key = payloadKey,
                                        filePath = input.filePath,
                                        contentType = input.contentType,
                                        previewThumbnail = thumbs.preview,
                                        descriptorContent = descriptorKey
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
                                        filePath = input.filePath,
                                        contentType = input.contentType,
                                        descriptorContent = input.displayName
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