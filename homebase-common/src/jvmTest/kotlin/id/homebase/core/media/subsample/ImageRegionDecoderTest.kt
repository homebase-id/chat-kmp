package id.homebase.core.media.subsample

import androidx.compose.ui.unit.IntRect
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals

internal fun createTestJpeg(width: Int, height: Int): ByteArray {
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    val g = image.createGraphics()
    g.color = Color.RED
    g.fillRect(0, 0, width / 2, height)
    g.color = Color.BLUE
    g.fillRect(width / 2, 0, width / 2, height)
    g.dispose()
    val out = ByteArrayOutputStream()
    ImageIO.write(image, "JPEG", out)
    return out.toByteArray()
}

class ImageRegionDecoderTest {
    @Test
    fun `reports correct image dimensions`() {
        val decoder = createImageRegionDecoder(createTestJpeg(800, 600))
        assertEquals(800, decoder.imageWidth)
        assertEquals(600, decoder.imageHeight)
        decoder.close()
    }

    @Test
    fun `decodes full image region`() {
        val decoder = createImageRegionDecoder(createTestJpeg(800, 600))
        val bitmap = decoder.decodeRegion(IntRect(0, 0, 800, 600), sampleSize = 1)
        assertEquals(800, bitmap.width)
        assertEquals(600, bitmap.height)
        decoder.close()
    }

    @Test
    fun `decodes sub-region`() {
        val decoder = createImageRegionDecoder(createTestJpeg(800, 600))
        val bitmap = decoder.decodeRegion(IntRect(0, 0, 400, 300), sampleSize = 1)
        assertEquals(400, bitmap.width)
        assertEquals(300, bitmap.height)
        decoder.close()
    }

    @Test
    fun `decodes with sample size`() {
        val decoder = createImageRegionDecoder(createTestJpeg(800, 600))
        val bitmap = decoder.decodeRegion(IntRect(0, 0, 800, 600), sampleSize = 2)
        assertEquals(400, bitmap.width)
        assertEquals(300, bitmap.height)
        decoder.close()
    }

    @Test
    fun `handles large image`() {
        val decoder = createImageRegionDecoder(createTestJpeg(4000, 3000))
        val bitmap = decoder.decodeRegion(IntRect(1000, 500, 1512, 1012), sampleSize = 1)
        assertEquals(512, bitmap.width)
        assertEquals(512, bitmap.height)
        decoder.close()
    }
}
