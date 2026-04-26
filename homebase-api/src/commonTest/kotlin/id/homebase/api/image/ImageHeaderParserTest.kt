package id.homebase.api.image

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ImageHeaderParserTest {

    @Test
    fun jpegSof0Returns800x600() {
        // Minimal JPEG: SOI + APP0 (skipped) + SOF0 with 800x600
        val app0 = byteArrayOf(
            0xFF.toByte(), 0xE0.toByte(),
            0x00, 0x10, // length 16
            'J'.code.toByte(), 'F'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 0x00,
            0x01, 0x01, 0x00, 0x00, 0x48, 0x00, 0x48, 0x00, 0x00,
        )
        val sof = byteArrayOf(
            0xFF.toByte(), 0xC0.toByte(),
            0x00, 0x11, // length 17
            0x08, // precision
            0x02.toByte(), 0x58.toByte(), // height = 600
            0x03.toByte(), 0x20.toByte(), // width = 800
            0x03, // components
            0x01, 0x22, 0x00,
            0x02, 0x11, 0x01,
            0x03, 0x11, 0x01,
        )
        val header = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + app0 + sof

        val size = ImageHeaderParser.parse(header)
        assertEquals(ImageSize(800, 600), size)
    }

    @Test
    fun pngIhdrReturnsCorrectSize() {
        val sig = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        )
        // IHDR: 4 bytes length(=13), 4 bytes type, 4 bytes width, 4 bytes height, ...
        val ihdr = byteArrayOf(
            0x00, 0x00, 0x00, 0x0D,
            'I'.code.toByte(), 'H'.code.toByte(), 'D'.code.toByte(), 'R'.code.toByte(),
            0x00, 0x00, 0x04.toByte(), 0x00.toByte(), // width = 1024
            0x00, 0x00, 0x03.toByte(), 0x00.toByte(), // height = 768
            0x08, 0x06, 0x00, 0x00, 0x00,
        )
        assertEquals(ImageSize(1024, 768), ImageHeaderParser.parse(sig + ihdr))
    }

    @Test
    fun gifReturnsLittleEndianSize() {
        val header = byteArrayOf(
            'G'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(),
            '8'.code.toByte(), '9'.code.toByte(), 'a'.code.toByte(),
            0x40, 0x00, // width = 64
            0x80.toByte(), 0x00, // height = 128
            0x00, 0x00,
        )
        assertEquals(ImageSize(64, 128), ImageHeaderParser.parse(header))
    }

    @Test
    fun unknownFormatReturnsNull() {
        assertNull(ImageHeaderParser.parse(ByteArray(64) { 0x42 }))
    }

    @Test
    fun tooShortReturnsNull() {
        assertNull(ImageHeaderParser.parse(byteArrayOf(0xFF.toByte(), 0xD8.toByte())))
    }
}
