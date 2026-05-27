package id.homebase.core.media.subsample

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.IntRect
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface

class NativeImageRegionDecoder(
    private val imageBytes: ByteArray,
) : ImageRegionDecoder {
    private val skiaImage: Image = Image.makeFromEncoded(imageBytes)
    override val imageWidth: Int = skiaImage.width
    override val imageHeight: Int = skiaImage.height

    override fun decodeRegion(rect: IntRect, sampleSize: Int): ImageBitmap {
        val outWidth = rect.width / sampleSize
        val outHeight = rect.height / sampleSize
        val surface = Surface.makeRasterN32Premul(outWidth, outHeight)
        surface.canvas.drawImageRect(
            skiaImage,
            Rect.makeXYWH(rect.left.toFloat(), rect.top.toFloat(), rect.width.toFloat(), rect.height.toFloat()),
            Rect.makeWH(outWidth.toFloat(), outHeight.toFloat()),
        )
        return surface.makeImageSnapshot().toComposeImageBitmap()
    }

    override fun close() = skiaImage.close()
}

actual fun createImageRegionDecoder(bytes: ByteArray): ImageRegionDecoder {
    return NativeImageRegionDecoder(bytes)
}
