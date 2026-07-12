package id.homebase.core.clipboard

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.UIKit.UIImagePNGRepresentation
import platform.UIKit.UIPasteboard
import platform.posix.memcpy

/**
 * Reads an image from the iOS system pasteboard ([UIPasteboard.generalPasteboard]).
 *
 * To preserve the ORIGINAL bytes — and in particular keep an animated GIF animated —
 * raw pasteboard data is preferred over `UIPasteboard.image`. Reading `.image` collapses
 * a GIF to a single static UIImage frame. So we try, in order:
 *   1. raw GIF bytes  ("com.compuserve.gif")
 *   2. raw PNG bytes  ("public.png")
 *   3. raw JPEG bytes ("public.jpeg")
 *   4. as a last resort, encode `pasteboard.image` to PNG via UIImagePNGRepresentation.
 *
 * The downstream handler sniffs the magic bytes to decide the content type / extension,
 * so returning the untouched GIF/PNG/JPEG bytes is enough to keep fidelity.
 *
 * Returns null when no image is on the pasteboard.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
actual fun getImageFromClipboard(): ByteArray? {
    val pasteboard = UIPasteboard.generalPasteboard

    val rawData: NSData? =
        pasteboard.dataForPasteboardType("com.compuserve.gif")
            ?: pasteboard.dataForPasteboardType("public.png")
            ?: pasteboard.dataForPasteboardType("public.jpeg")

    val data: NSData = rawData
        ?: pasteboard.image?.let { UIImagePNGRepresentation(it) }
        ?: return null

    return data.toByteArray()
}

actual suspend fun readClipboardImage(): ByteArray? = getImageFromClipboard()

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun NSData.toByteArray(): ByteArray? {
    val length = this.length.toInt()
    if (length <= 0) return null
    val bytes = ByteArray(length)
    bytes.usePinned { pinned -> memcpy(pinned.addressOf(0), this.bytes, this.length) }
    return bytes
}
