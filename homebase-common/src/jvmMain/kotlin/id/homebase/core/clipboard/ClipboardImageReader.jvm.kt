package id.homebase.core.clipboard

import java.awt.Image
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

actual fun getImageFromClipboard(): ByteArray? {
    return try {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
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
