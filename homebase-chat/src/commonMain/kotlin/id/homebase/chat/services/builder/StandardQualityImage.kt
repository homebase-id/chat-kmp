package id.homebase.chat.services.builder

import co.touchlab.kermit.Logger
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.image.ImageUtils
import id.homebase.api.image.createImageThumbnail
import id.homebase.api.image.standardPrimaryImage
import kotlin.math.max

/**
 * Swaps an image attachment's payload for a 1600px WebP so MediaQuality.STANDARD stops shipping
 * the full-resolution original. Returns [attachment] untouched whenever re-encoding would lose
 * something the receiver needs, or would cost bytes for nothing.
 *
 * The 1600px encode becomes the payload rather than a fifth thumbnail because the server builds a
 * file's stored payload list from the multipart parts that actually arrive — a payload key with no
 * bytes gets its thumbnails orphaned and reaped.
 */
internal suspend fun standardQualityImage(
    attachment: AttachmentInput,
    sourceBytes: ByteArray,
    payloadKey: String,
    fileOperationsProvider: FileOperationsProvider,
): AttachmentInput {
    // Stickers are already bounded at 512px by StickerImageProcessor, and a lossy re-encode would
    // chew on their alpha. GIF's payload is the animated original — the still encoder would freeze
    // it. SVG is vector: rasterising it into the payload throws away the resolution independence
    // the ladder is built to exploit.
    if (attachment.forceSticker) return attachment
    if (attachment.contentType == "image/gif") return attachment
    if (attachment.contentType == "image/svg+xml") return attachment

    // Decoding an undecodable source throws, so the size probe belongs inside the fail-soft too.
    return try {
        val natural = ImageUtils.getNaturalSize(sourceBytes)
        val alreadyFits =
            max(natural.pixelWidth, natural.pixelHeight) <= standardPrimaryImage.maxPixelDimension &&
                sourceBytes.size <= standardPrimaryImage.maxBytes
        if (alreadyFits) {
            // Re-encoding would only add generational loss. It also sidesteps
            // createImageThumbnail's pass-through fast path, which hands back the source bytes
            // labelled image/webp.
            attachment
        } else {
            val encoded = createImageThumbnail(sourceBytes, payloadKey, standardPrimaryImage)
            val path =
                fileOperationsProvider.writeBytesToTempFile(
                    encoded.thumbnailBytes,
                    "img_std_",
                    ".webp",
                )
            attachment.copy(filePath = path, contentType = "image/webp")
        }
    } catch (e: Exception) {
        Logger.w(tag = "StandardQualityImage", throwable = e) {
            "Downscale failed for $payloadKey; sending the original instead"
        }
        attachment
    }
}
