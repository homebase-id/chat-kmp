package id.homebase.api.image

import org.jetbrains.skia.Image
import kotlin.test.Test

class ZzWebAspectProbeTest {

    // Verbatim copy of coil3's jsCommon getJpegSizeOrNull (the size source the
    // wasmJs decoder feeds to createImageBitmap's resizeWidth/resizeHeight).
    private fun jpegHeaderSize(bytes: ByteArray): Pair<Int, Int>? {
        fun Byte.asInt() = this.toUInt() and 0xFFu
        fun int16(b1: Byte, b2: Byte): UInt = (b1.asInt() shl 8) or b2.asInt()
        if (bytes.size < 10) return null
        if (int16(bytes[0], bytes[1]) == 0xFFD8u) {
            var offset = 2
            while (offset < bytes.size - 6) {
                val marker = int16(bytes[offset], bytes[offset + 1])
                offset += 2
                if (marker in 0xFFC0u..0xFFCFu && marker != 0xFFC4u && marker != 0xFFC8u && marker != 0xFFCCu) {
                    val height = int16(bytes[offset + 3], bytes[offset + 4])
                    val width = int16(bytes[offset + 5], bytes[offset + 6])
                    return width.toInt() to height.toInt()
                }
                val segmentLength = int16(bytes[offset], bytes[offset + 1])
                offset += segmentLength.toInt()
            }
        }
        return null
    }

    @Test
    fun probe() {
        val names = listOf(1, 2, 3, 4, 5, 6, 7, 8).flatMap {
            listOf("Landscape_$it.jpg", "Portrait_$it.jpg")
        }
        println("fixture | exifOrientation | coilHeaderWxH | skiaDecodedWxH | ImageUtils.getNaturalSize")
        for (n in names) {
            val bytes = ImageTestHelper.loadImage("orientation/$n")
            val md = readImageMetadata(bytes)
            val header = jpegHeaderSize(bytes)
            val img = Image.makeFromEncoded(bytes)
            val skia = "${img.width}x${img.height}"
            img.close()
            val natural = ImageUtils.getNaturalSize(bytes).let { "${it.width}x${it.height}" }
            println("$n | ${md?.orientation} | ${header?.first}x${header?.second} | $skia | $natural | exifDims=${md?.pixelWidth}x${md?.pixelHeight}")
        }
    }
}
