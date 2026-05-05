package id.homebase.chat.services.builder

import id.homebase.api.client.drives.files.PayloadFile
import id.homebase.api.client.drives.upload.EmbeddedThumb
import id.homebase.api.client.location.LocationPreview
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.image.createThumbnails
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.api.util.truncateToCodePoints
import id.homebase.chat.services.ChatProtocol
import id.homebase.chat.services.PayloadBundle
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@OptIn(ExperimentalEncodingApi::class)
object LocationPreviewPayloadBuilder {

    suspend fun build(
        locationPreview: LocationPreview,
        fileOperationsProvider: FileOperationsProvider,
    ): PayloadBundle {
        val imageUrl = locationPreview.imageUrl
        val hasBase64 = imageUrl != null && imageUrl.contains("base64,")

        val imageBytes = if (hasBase64 && imageUrl.isNotEmpty()) {
            try {
                Base64.decode(imageUrl.substringAfter("base64,"))
            } catch (_: Exception) {
                ByteArray(0)
            }
        } else {
            ByteArray(0)
        }

        val actualHasImage = imageBytes.isNotEmpty()

        var descriptorContent = buildDescriptorJson(locationPreview, actualHasImage, maxAddrLen = null)
        if (descriptorContent.encodeToByteArray().size > ChatProtocol.MaxDescriptorContentLength) {
            descriptorContent = buildDescriptorJson(locationPreview, actualHasImage, maxAddrLen = 200)
        }

        val mimeType = if (actualHasImage && imageUrl != null) {
            imageUrl.substringAfter("data:").substringBefore(";")
        } else {
            "application/octet-stream"
        }

        val tempPath = fileOperationsProvider.writeBytesToTempFile(
            bytes = imageBytes, prefix = "location_preview", suffix = ".dat"
        )

        val tinyThumb: EmbeddedThumb? = if (actualHasImage) {
            try {
                val (_, thumb, _) = createThumbnails(imageBytes, ChatProtocol.PAYLOAD_KEY_LOCATION)
                thumb
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }

        return PayloadBundle(
            payloads = listOf(
                PayloadFile(
                    key = ChatProtocol.PAYLOAD_KEY_LOCATION,
                    filePath = tempPath,
                    contentType = mimeType,
                    descriptorContent = descriptorContent,
                    previewThumbnail = tinyThumb,
                )
            ),
            thumbnails = emptyList(),
            previewThumbs = listOfNotNull(tinyThumb),
        )
    }

    private fun buildDescriptorJson(
        locationPreview: LocationPreview,
        hasImage: Boolean,
        maxAddrLen: Int?,
    ): String {
        val descriptor = LocationPreviewDescriptor(
            lat = locationPreview.lat,
            lon = locationPreview.lon,
            address = if (maxAddrLen != null) {
                locationPreview.address.truncateToCodePoints(maxAddrLen)
            } else {
                locationPreview.address
            },
            hasImage = hasImage,
            imageWidth = if (hasImage) locationPreview.imageWidth else null,
            imageHeight = if (hasImage) locationPreview.imageHeight else null,
        )
        return OdinSystemSerializer.serialize(listOf(descriptor))
    }
}
