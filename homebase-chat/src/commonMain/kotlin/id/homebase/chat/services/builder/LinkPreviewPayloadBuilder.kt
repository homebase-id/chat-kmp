package id.homebase.chat.services.builder

import id.homebase.api.client.drives.files.PayloadFile
import id.homebase.api.client.drives.upload.EmbeddedThumb
import id.homebase.api.client.link.LinkPreview
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.image.createThumbnails
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.util.truncateToCodePoints
import id.homebase.chat.services.ChatProtocol
import id.homebase.chat.services.PayloadBundle
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@OptIn(ExperimentalEncodingApi::class)
object LinkPreviewPayloadBuilder {

    suspend fun build(
        linkPreview: LinkPreview, fileOperationsProvider: FileOperationsProvider
    ): PayloadBundle {
        val imageUrl = linkPreview.imageUrl
        val hasBase64 = imageUrl != null && imageUrl.contains("base64,")

        val imageBytes = if (hasBase64 && imageUrl.isNotEmpty()) {
            try {
                Base64.decode(imageUrl.substringAfter("base64,"))
            } catch (e: Exception) {
                ByteArray(0)
            }
        } else {
            ByteArray(0)
        }

        val actualHasImage = imageBytes.isNotEmpty()

        // 1. Build descriptorContent (stripped JSON, no image base64, ≤1024 bytes)
        var descriptorContent = buildDescriptorJson(linkPreview, actualHasImage, maxTextLen = null)
        if (descriptorContent.encodeToByteArray().size > ChatProtocol.MaxDescriptorContentLength) {
            descriptorContent = buildDescriptorJson(linkPreview, actualHasImage, maxTextLen = 100)
        }

        val mimeType = if (actualHasImage && imageUrl != null) {
            imageUrl.substringAfter("data:").substringBefore(";")
        } else {
            "application/octet-stream"
        }

        // 3. Always write to temp file for encryption pipeline.
        // Sentinel byte when no image: AesCbc.encrypt rejects empty data
        // (would crash addMessage for URLs whose page has no og:image, e.g. diku.dk).
        // Receiver gates image rendering on descriptor.hasImage, so this byte is never read.
        val payloadBytes = if (imageBytes.isNotEmpty()) imageBytes else byteArrayOf(0x00)
        val tempPath = fileOperationsProvider.writeBytesToTempFile(
            bytes = payloadBytes, prefix = "link_preview", suffix = ".dat"
        )

        // 4. Generate tinyThumb if image exists
        val tinyThumb: EmbeddedThumb? = if (actualHasImage) {
            try {
                val (_, thumb, _) = createThumbnails(imageBytes, ChatProtocol.PAYLOAD_KEY_LINKS)
                thumb
            } catch (e: Exception) {
                null
            }
        } else {
            null
        }

        return PayloadBundle(
            payloads = listOf(
                PayloadFile(
                    key = ChatProtocol.PAYLOAD_KEY_LINKS,
                    filePath = tempPath,
                    contentType = mimeType,
                    descriptorContent = descriptorContent,
                    previewThumbnail = tinyThumb
                )
            ), thumbnails = emptyList(), previewThumbs = listOfNotNull(tinyThumb)
        )
    }

    private fun buildDescriptorJson(
        linkPreview: LinkPreview, hasImage: Boolean, maxTextLen: Int?
    ): String {
        val descriptor = LinkPreviewDescriptor(
            url = linkPreview.url,
            hasImage = hasImage,
            imageWidth = if (hasImage) linkPreview.imageWidth else null,
            imageHeight = if (hasImage) linkPreview.imageHeight else null,
            description = if (maxTextLen != null) {
                linkPreview.description.truncateToCodePoints(maxTextLen)
            } else {
                linkPreview.description
            },
            title = if (maxTextLen != null) {
                linkPreview.title.truncateToCodePoints(maxTextLen)
            } else {
                linkPreview.title
            }
        )
        return OdinSystemSerializer.serialize(listOf(descriptor))
    }
}
