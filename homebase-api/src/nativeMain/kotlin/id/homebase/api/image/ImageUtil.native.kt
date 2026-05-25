package id.homebase.api.image

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.dataWithBytes

/**
 * iOS: Convert HEIC to JPEG using native UIImage APIs.
 *
 * The rest of the image pipeline (the [ImageUtils] object and [toImageBitmap]) is shared
 * across Desktop/iOS/Web in the `skiaMain` source set — only this HEIC bridge is per-platform.
 */
@OptIn(ExperimentalForeignApi::class)
actual fun convertHeicToJpeg(heicBytes: ByteArray): ByteArray? {
    return try {
        val nsData = heicBytes.usePinned { pinned ->
            platform.Foundation.NSData.dataWithBytes(pinned.addressOf(0), heicBytes.size.toULong())
        }
        val uiImage = platform.UIKit.UIImage.imageWithData(nsData) ?: return null
        val jpegData = platform.UIKit.UIImageJPEGRepresentation(uiImage, 0.95) ?: return null
        ByteArray(jpegData.length.toInt()).also { bytes ->
            bytes.usePinned { pinned ->
                platform.posix.memcpy(pinned.addressOf(0), jpegData.bytes, jpegData.length)
            }
        }
    } catch (_: Exception) {
        null
    }
}
