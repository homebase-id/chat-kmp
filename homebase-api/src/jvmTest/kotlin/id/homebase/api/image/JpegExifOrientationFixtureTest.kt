package id.homebase.api.image

import org.jetbrains.skia.Image
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins [readJpegExifOrientation] against the canonical orientation fixtures and
 * against the fact the web decoder depends on: for orientations 5-8 the JPEG
 * frame header dimensions are the *transposed* display dimensions, which is why
 * a browser-decoded (orientation-applied) bitmap must not be resized to them.
 */
class JpegExifOrientationFixtureTest {

    @Test
    fun everyFixtureOrientationMatchesExifReader() {
        for (orientation in 1..8) {
            for (prefix in listOf("Landscape", "Portrait")) {
                val bytes = ImageTestHelper.loadImage("orientation/${prefix}_$orientation.jpg")
                assertEquals(orientation, readJpegExifOrientation(bytes), "$prefix#$orientation")
            }
        }
    }

    @Test
    fun quarterTurnFixtures_decodeTransposedRelativeToFrameHeader() {
        for (orientation in 1..8) {
            val bytes = ImageTestHelper.loadImage("orientation/Landscape_$orientation.jpg")
            val header = jpegFrameSize(bytes)
            val decoded = Image.makeFromEncoded(bytes).let { image ->
                (image.width to image.height).also { image.close() }
            }
            if (jpegOrientationSwapsDimensions(orientation)) {
                assertEquals(header.second to header.first, decoded, "orientation $orientation")
            } else {
                assertEquals(header, decoded, "orientation $orientation")
            }
        }
    }

    /** SOF frame dimensions — EXIF orientation NOT applied. */
    private fun jpegFrameSize(bytes: ByteArray): Pair<Int, Int> {
        fun u8(index: Int) = bytes[index].toInt() and 0xFF
        fun u16(index: Int) = (u8(index) shl 8) or u8(index + 1)
        var offset = 2
        while (offset < bytes.size - 6) {
            val marker = u16(offset)
            offset += 2
            if (marker in 0xFFC0..0xFFCF && marker != 0xFFC4 && marker != 0xFFC8 && marker != 0xFFCC) {
                return u16(offset + 5) to u16(offset + 3)
            }
            offset += u16(offset)
        }
        error("No SOF marker")
    }
}
