package id.homebase.core.media.subsample

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.IntRect

class WebImageRegionDecoder : ImageRegionDecoder {
    override val imageWidth: Int = 0
    override val imageHeight: Int = 0
    override fun decodeRegion(rect: IntRect, sampleSize: Int): ImageBitmap {
        throw UnsupportedOperationException("Region decoding not supported on web")
    }
    override fun close() {}
}

actual fun createImageRegionDecoder(bytes: ByteArray): ImageRegionDecoder {
    return WebImageRegionDecoder()
}
