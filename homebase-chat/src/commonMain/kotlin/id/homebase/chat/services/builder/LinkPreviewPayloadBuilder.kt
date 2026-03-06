package id.homebase.chat.services.builder

import id.homebase.api.client.drives.files.PayloadFile
import id.homebase.api.client.drives.upload.EmbeddedThumb
import id.homebase.api.client.link.LinkPreview
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.image.createThumbnails
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.chat.services.ChatProtocol
import id.homebase.chat.services.PayloadBundle
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@OptIn(ExperimentalEncodingApi::class)
object LinkPreviewPayloadBuilder {

    suspend fun build(
        linkPreview: LinkPreview, fileOperationsProvider: FileOperationsProvider
    ): PayloadBundle {
        // 1. Build descriptorContent (stripped JSON, no image base64, ≤1024 bytes)
        var descriptorContent = buildDescriptorJson(linkPreview, maxDescLen = null)
        if (descriptorContent.encodeToByteArray().size > ChatProtocol.MAX_PAYLOAD_DESCRIPTOR_BYTES) {
            descriptorContent = buildDescriptorJson(linkPreview, maxDescLen = 100)
        }

        // 2. Extract image bytes or use empty
        val imageUrl = linkPreview.imageUrl
        val hasImage = imageUrl != null && imageUrl.contains("base64,")

        val imageBytes = if (hasImage && imageUrl != null) {
            Base64.decode(imageUrl.substringAfter("base64,"))
        } else {
            ByteArray(0)
        }

        val mimeType = if (hasImage && imageUrl != null) {
            imageUrl.substringAfter("data:").substringBefore(";")
        } else {
            "application/octet-stream"
        }

        // 3. Always write to temp file for encryption pipeline
        val tempPath = fileOperationsProvider.writeBytesToTempFile(
            bytes = imageBytes, prefix = "link_preview", suffix = ".dat"
        )

        // 4. Generate tinyThumb if image exists
        val tinyThumb: EmbeddedThumb? = if (hasImage && imageBytes.isNotEmpty()) {
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

    private fun buildDescriptorJson(linkPreview: LinkPreview, maxDescLen: Int?): String {
        val descriptor = LinkPreviewDescriptor(
            url = linkPreview.url,
            hasImage = linkPreview.imageUrl != null,
            imageWidth = linkPreview.imageWidth,
            imageHeight = linkPreview.imageHeight,
            description = if (maxDescLen != null) {
                linkPreview.description.take(maxDescLen)
            } else {
                linkPreview.description
            },
            title = linkPreview.title
        )
        return OdinSystemSerializer.serialize(listOf(descriptor))
    }
}
