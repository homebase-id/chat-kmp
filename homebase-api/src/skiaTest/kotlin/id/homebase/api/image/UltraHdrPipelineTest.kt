package id.homebase.api.image

import kotlinx.coroutines.test.runTest
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Color
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.Surface
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** THUMBNAILS.md, "HDR": desktop, web and iOS send HDR through the same shared container as Android. */
class UltraHdrPipelineTest {
    private val metadata = GainmapMetadata(listOf(0.0), listOf(2.0), listOf(1.0), listOf(1.0 / 64), listOf(1.0 / 64), 0.0, 2.0)

    private fun jpeg(width: Int, height: Int, color: Int): ByteArray {
        val surface = Surface.makeRasterN32Premul(width, height)
        surface.canvas.clear(color)
        return surface.makeImageSnapshot().encodeToData(EncodedImageFormat.JPEG, 90)!!.bytes
    }

    private fun ultraHdrSource() = UltraHdr.join(jpeg(1600, 1200, 0xFF6699CC.toInt()), jpeg(400, 300, Color.makeRGB(128, 128, 128)), metadata)

    @Test
    fun anHdPhotoKeepsItsGainmapScaledWithIt() = runTest {
        val images = createMediaImages(ultraHdrSource(), "k", MediaQuality.HD, hdr = true)

        val final = assertNotNull(images.finalImage)
        assertEquals(ImageFormat.JPEG, final.format)
        val gainmap = assertNotNull(UltraHdr.gainmapOf(final.bytes))
        assertEquals(metadata, gainmap.metadata)
        val map = Image.makeFromEncoded(gainmap.jpeg)
        assertEquals(final.width / 4, map.width)
        // Gain-map values are data, not colour: no colour conversion may move them.
        val pixels = Bitmap.makeFromImage(map)
        val grey = Color.getR(pixels.getColor(map.width / 2, map.height / 2))
        assertTrue(abs(grey - 128) <= 3, "gain map value moved to $grey")
    }

    @Test
    fun standardStaysSdr() = runTest {
        val images = createMediaImages(ultraHdrSource(), "k", MediaQuality.STANDARD, hdr = true)
        assertNull(UltraHdr.gainmapOf(assertNotNull(images.finalImage).bytes))
    }
}
