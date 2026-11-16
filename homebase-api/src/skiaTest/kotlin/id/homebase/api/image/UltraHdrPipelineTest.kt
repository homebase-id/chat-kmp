package id.homebase.api.image

import kotlinx.coroutines.test.runTest
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Color
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ColorAlphaType
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
    fun aSingleChannelGainmapKeepsItsDarkValues() = runTest {
        // Cameras write the gain map as a grey JPEG; colour-converting it would move a 10 to about 3.
        val grey = Surface.makeRaster(ImageInfo(400, 300, ColorType.GRAY_8, ColorAlphaType.OPAQUE))
        grey.canvas.clear(Color.makeRGB(10, 10, 10))
        val greyJpeg = grey.makeImageSnapshot().encodeToData(EncodedImageFormat.JPEG, 95)!!.bytes
        val source = UltraHdr.join(jpeg(1600, 1200, 0xFF6699CC.toInt()), greyJpeg, metadata)

        val final = assertNotNull(createMediaImages(source, "k", MediaQuality.HD, hdr = true).finalImage)

        val map = Image.makeFromEncoded(assertNotNull(UltraHdr.gainmapOf(final.bytes)).jpeg)
        val value = Color.getR(Bitmap.makeFromImage(map).getColor(map.width / 2, map.height / 2))
        assertTrue(abs(value - 10) <= 2, "gain map value moved from 10 to $value")
    }

    @Test
    fun aBrokenGainmapStillGivesTheSdrImage() = runTest {
        // The gain map's frame header turned into a comment: its metadata reads, its pixels won't decode.
        val broken = ultraHdrSource()
        val gainmapStart = UltraHdr.gainmapRange(broken)!!.first.toInt()
        val sof = (gainmapStart until broken.size - 1).first { broken[it] == 0xFF.toByte() && broken[it + 1] == 0xC0.toByte() }
        broken[sof + 1] = 0xFE.toByte()

        val final = assertNotNull(createMediaImages(broken, "k", MediaQuality.HD, hdr = true).finalImage)

        assertEquals(ImageFormat.WEBP, final.format)
        assertEquals(1600, final.width)
    }

    @Test
    fun standardStaysSdr() = runTest {
        val images = createMediaImages(ultraHdrSource(), "k", MediaQuality.STANDARD, hdr = true)
        assertNull(UltraHdr.gainmapOf(assertNotNull(images.finalImage).bytes))
    }
}
