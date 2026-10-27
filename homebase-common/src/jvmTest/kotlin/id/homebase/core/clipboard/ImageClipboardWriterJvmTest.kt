package id.homebase.core.clipboard

import java.awt.datatransfer.Clipboard
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ImageClipboardWriterJvmTest {

    private fun encoded(format: String): ByteArray {
        val image = BufferedImage(6, 4, BufferedImage.TYPE_INT_RGB)
        for (x in 0 until 6) for (y in 0 until 4) image.setRGB(x, y, if (x < 3) 0xFF0000 else 0x0000FF)
        return ByteArrayOutputStream().also { ImageIO.write(image, format, it) }.toByteArray()
    }

    private val pngMagic = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())

    @Test
    fun pngPassesThroughUntouched() {
        val png = encoded("png")
        assertContentEquals(png, toClipboardPng(png, "image/png"))
    }

    @Test
    fun jpegIsReencodedAsPng() {
        val png = toClipboardPng(encoded("jpg"), "image/jpeg")
        assertContentEquals(pngMagic, png.copyOfRange(0, 4))
        assertEquals(6, ImageIO.read(ByteArrayInputStream(png)).width)
    }

    @Test
    fun writtenImageReadsBackFromTheClipboard() {
        val clipboard = Clipboard("test")
        writePngToClipboard(clipboard, encoded("png"))

        val readBack = assertNotNull(readImageFromClipboard(clipboard))
        val image = ImageIO.read(ByteArrayInputStream(readBack))
        assertEquals(6 to 4, image.width to image.height)
        assertEquals(0xFF0000, image.getRGB(0, 0) and 0xFFFFFF)
        assertEquals(0x0000FF, image.getRGB(5, 3) and 0xFFFFFF)
    }
}
