package id.homebase.core.clipboard

import java.awt.Image
import java.awt.Toolkit
import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.DataFlavor
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import javax.imageio.ImageIO

private val imageFileExtensions =
    setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "heic", "heif")

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

        // 2. A copied image FILE (e.g. from Finder/Explorer) exposes javaFileListFlavor,
        //    not an image flavor — the reader used to return null for it. Read the file's
        //    bytes directly, preserving the original encoding like the raw-byte path above.
        readImageFileBytes(clipboard)?.let { return it }

        // 3. Fall back to the decoded image flavor, re-encoded as PNG. This covers
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

/**
 * Reads the first image FILE off the clipboard's [DataFlavor.javaFileListFlavor] — how an image
 * copied from Finder/Explorer arrives (as a file reference, not image pixels). Returns the file's
 * raw bytes so the original encoding is preserved, gated on a known image extension so a copied
 * document isn't attached. Null when no image file is present.
 */
private fun readImageFileBytes(clipboard: Clipboard): ByteArray? {
    return try {
        if (!clipboard.isDataFlavorAvailable(DataFlavor.javaFileListFlavor)) return null
        val files = clipboard.getData(DataFlavor.javaFileListFlavor) as? List<*> ?: return null
        val imageFile = files.filterIsInstance<File>().firstOrNull {
            it.isFile && it.extension.lowercase() in imageFileExtensions
        } ?: return null
        imageFile.readBytes().takeIf { it.isNotEmpty() }
    } catch (_: Exception) {
        null
    }
}
