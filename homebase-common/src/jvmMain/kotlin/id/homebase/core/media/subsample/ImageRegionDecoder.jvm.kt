package id.homebase.core.media.subsample

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.IntRect
import java.awt.Rectangle
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

class JvmImageRegionDecoder(
    private val bytes: ByteArray,
) : ImageRegionDecoder {
    override val imageWidth: Int
    override val imageHeight: Int

    init {
        val stream = ImageIO.createImageInputStream(ByteArrayInputStream(bytes))
        val reader = ImageIO.getImageReaders(stream).next()
        reader.input = stream
        imageWidth = reader.getWidth(0)
        imageHeight = reader.getHeight(0)
        reader.dispose()
    }

    override fun decodeRegion(rect: IntRect, sampleSize: Int): ImageBitmap {
        val stream = ImageIO.createImageInputStream(ByteArrayInputStream(bytes))
        val reader = ImageIO.getImageReaders(stream).next()
        reader.input = stream
        val param = reader.defaultReadParam.apply {
            sourceRegion = Rectangle(rect.left, rect.top, rect.width, rect.height)
            if (sampleSize > 1) {
                setSourceSubsampling(sampleSize, sampleSize, 0, 0)
            }
        }
        val bufferedImage = reader.read(0, param)
        reader.dispose()
        return bufferedImage.toComposeImageBitmap()
    }

    override fun close() {}
}

actual fun createImageRegionDecoder(bytes: ByteArray): ImageRegionDecoder {
    return JvmImageRegionDecoder(bytes)
}
