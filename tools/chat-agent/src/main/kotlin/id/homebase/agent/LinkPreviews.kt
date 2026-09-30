package id.homebase.agent

import id.homebase.api.client.link.LinkPreview
import id.homebase.api.client.link.LinkPreviewProvider
import id.homebase.api.client.drives.files.PayloadFile
import id.homebase.api.client.drives.upload.EmbeddedThumb
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.util.truncateToCodePoints
import id.homebase.chat.services.ChatProtocol
import id.homebase.chat.services.builder.LinkPreviewDescriptor
import id.homebase.upload.PayloadBundle
import kotlin.io.encoding.Base64

private val URL_REGEX = Regex("https?://[^\\s<>\"'`]+", RegexOption.IGNORE_CASE)
private const val URL_TRAILING = ".,;:!?)]}>*_"

fun firstUrl(text: String): String? =
    URL_REGEX.find(text)?.value?.trimEnd { it in URL_TRAILING }?.takeIf { it.length > 8 }

fun interface LinkPreviewSource {
    suspend fun preview(url: String): LinkPreview?
}

// the identity server fetches the page; this machine never requests chat-supplied URLs
fun serverLinkPreviews(session: Session) = LinkPreviewSource { url ->
    LinkPreviewProvider(session.http, session.credentials).getLinkPreview(url)
}

private fun linkDescriptor(preview: LinkPreview, hasImage: Boolean, maxTextLen: Int?) = OdinSystemSerializer.serialize(
    listOf(
        LinkPreviewDescriptor(
            url = preview.url,
            hasImage = hasImage,
            imageWidth = preview.imageWidth.takeIf { hasImage },
            imageHeight = preview.imageHeight.takeIf { hasImage },
            description = maxTextLen?.let { preview.description.truncateToCodePoints(it) } ?: preview.description,
            title = maxTextLen?.let { preview.title.truncateToCodePoints(it) } ?: preview.title,
        ),
    ),
)

// javax.imageio tiny thumb: the app's createThumbnails needs Skia
suspend fun buildLinkPreviewBundle(preview: LinkPreview, fileOps: FileOperationsProvider): PayloadBundle {
    val imageUrl = preview.imageUrl
    val imageBytes = if (imageUrl != null && imageUrl.contains("base64,")) {
        runCatching { Base64.decode(imageUrl.substringAfter("base64,")) }.getOrDefault(ByteArray(0))
    } else {
        ByteArray(0)
    }
    val tiny: EmbeddedThumb? = decodeImage(imageBytes)?.let { runCatching { tinyThumb(it.image) }.getOrNull() }
    val hasImage = tiny != null
    var descriptor = linkDescriptor(preview, hasImage, null)
    if (descriptor.encodeToByteArray().size > ChatProtocol.MaxDescriptorContentLength) descriptor = linkDescriptor(preview, hasImage, 100)
    val mimeType = if (hasImage && imageUrl != null) imageUrl.substringAfter("data:").substringBefore(";") else "application/octet-stream"
    val path = fileOps.writeBytesToTempFile(bytes = if (hasImage) imageBytes else byteArrayOf(0x00), prefix = "link_preview", suffix = ".dat")
    return PayloadBundle(
        payloads = listOf(PayloadFile(key = ChatProtocol.PAYLOAD_KEY_LINKS, filePath = path, contentType = mimeType, descriptorContent = descriptor, previewThumbnail = tiny)),
        thumbnails = emptyList(),
        previewThumbs = listOfNotNull(tiny),
    )
}
