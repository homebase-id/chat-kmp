package id.homebase.core.media.subsample

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.IntRect

interface ImageRegionDecoder {
    val imageWidth: Int
    val imageHeight: Int
    fun decodeRegion(rect: IntRect, sampleSize: Int): ImageBitmap
    fun close()
}

expect fun createImageRegionDecoder(bytes: ByteArray): ImageRegionDecoder
