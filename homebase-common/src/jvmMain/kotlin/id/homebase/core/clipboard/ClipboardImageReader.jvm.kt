package id.homebase.core.clipboard

import java.awt.Image
import java.awt.Toolkit
import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.DataFlavor
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.InputStream
import javax.imageio.ImageIO

actual fun getImageFromClipboard(): ByteArray? =
    readImageFromClipboard(Toolkit.getDefaultToolkit().systemClipboard)

/**
 * Testable core of [getImageFromClipboard] that takes the [Clipboard] explicitly so a
 * unit test can inject a fake holding a raw image/gif flavor and assert the original
 * bytes survive (no PNG re-encode / GIF flattening).
 */
internal fun readImageFromClipboard(clipboard: Clipboard): ByteArray? {
    return try {
        // 1. Prefer RAW image bytes off the clipboard (image/gif, then image/png).
        //    DataFlavor.imageFlavor decodes to a single BufferedImage frame, which
        //    FLATTENS an animated GIF. Reading the raw byte flavor preserves every
        //    frame and the original encoding, so a pasted GIF still animates on the
        //    receiver. Many clipboard sources (browsers, screenshot tools) only put
        //    imageFlavor on the clipboard, so this is best-effort with a fallback.
        readRawImageBytes(clipboard, "image/gif")?.let { return it }
        readRawImageBytes(clipboard, "image/png")?.let { return it }

        // 2. Fall back to the decoded image flavor, re-encoded as PNG. This covers
        //    in-memory bitmaps (e.g. a screenshot) that expose no raw byte flavor.
        if (!clipboard.isDataFlavorAvailable(DataFlavor.imageFlavor)) return null
        val image = clipboard.getData(DataFlavor.imageFlavor) as? Image ?: return null
        val bufferedImage = if (image is BufferedImage) {
            image
        } else {
            val bi = BufferedImage(
                image.getWidth(null),
                image.getHeight(null),
                BufferedImage.TYPE_INT_ARGB
            )
            val g = bi.createGraphics()
            g.drawImage(image, 0, 0, null)
            g.dispose()
            bi
        }

        val outputStream = ByteArrayOutputStream()
        ImageIO.write(bufferedImage, "png", outputStream)
        outputStream.toByteArray()
    } catch (_: Exception) {
        null
    }
}

/**
 * Reads raw clipboard bytes for a specific image MIME type (e.g. "image/gif") as an
 * InputStream-backed DataFlavor, returning the unmodified bytes or null if that flavor
 * is not present / fails to read. Returning the original bytes keeps animated GIFs intact.
 */
private fun readRawImageBytes(clipboard: Clipboard, mimeType: String): ByteArray? {
    return try {
        val flavor = DataFlavor("$mimeType; class=java.io.InputStream")
        if (!clipboard.isDataFlavorAvailable(flavor)) return null
        val data = clipboard.getData(flavor) as? InputStream ?: return null
        data.use { it.readBytes() }.takeIf { it.isNotEmpty() }
    } catch (_: Exception) {
        null
    }
}
