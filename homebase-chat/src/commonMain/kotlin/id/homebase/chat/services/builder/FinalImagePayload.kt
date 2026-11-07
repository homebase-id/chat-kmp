package id.homebase.chat.services.builder

import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.lib.image.ImageFormatDetector

private val FORMATS_EVERY_CLIENT_CAN_DECODE = setOf("jpeg", "png", "webp")

/**
 * Swaps an image attachment's file for the final image a Standard or HD send makes. Returns
 * [attachment] untouched when there's no final image, or when the source already fits and
 * re-encoding wouldn't save at least a quarter, since that would only add generational loss.
 *
 * It becomes the payload rather than one more thumbnail because the server builds a file's stored
 * payload list from the multipart parts that actually arrive: a payload key with no bytes gets its
 * thumbnails orphaned and reaped.
 */
internal suspend fun finalImagePayload(
    attachment: AttachmentInput,
    thumbs: ThumbnailResult,
    fileOperationsProvider: FileOperationsProvider,
): AttachmentInput {
    val image = thumbs.finalImage ?: return attachment
    val sourceFits = thumbs.displayedSize?.let { it.pixelWidth == image.width && it.pixelHeight == image.height } == true
    val savesEnough = image.bytes.size.toLong() * 4 <= thumbs.sourceBytes.size.toLong() * 3
    if (sourceFits && !savesEnough && isKeepable(thumbs.sourceBytes)) return attachment

    val path = fileOperationsProvider.writeBytesToTempFile(image.bytes, "img_final_", ".webp")
    return attachment.copy(filePath = path, contentType = "image/webp")
}

private fun isKeepable(bytes: ByteArray) =
    ImageFormatDetector.detectFormat(bytes).lowercase().substringAfter('/') in FORMATS_EVERY_CLIENT_CAN_DECODE
