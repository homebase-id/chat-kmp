package id.homebase.core.media.subsample

import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntRect

class AndroidImageRegionDecoder(
    private val decoder: BitmapRegionDecoder,
) : ImageRegionDecoder {
    override val imageWidth: Int get() = decoder.width
    override val imageHeight: Int get() = decoder.height

    override fun decodeRegion(rect: IntRect, sampleSize: Int): ImageBitmap {
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
        }
        val androidRect = Rect(rect.left, rect.top, rect.right, rect.bottom)
        return decoder.decodeRegion(androidRect, options).asImageBitmap()
    }

    override fun close() = decoder.recycle()
}

actual fun createImageRegionDecoder(bytes: ByteArray): ImageRegionDecoder {
    val decoder = BitmapRegionDecoder.newInstance(bytes, 0, bytes.size, false)
        ?: throw IllegalArgumentException("Failed to create BitmapRegionDecoder")
    return AndroidImageRegionDecoder(decoder)
}
