package id.homebase.chat.services.image

import id.homebase.api.image.ImageUtils
import id.homebase.api.lib.image.ImageFormatDetector
import kotlinx.coroutines.test.runTest
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Exercises the cut-out sizing/encoding on the JVM, where [ImageUtils] is the real
 * Skia backend — so the resize, the alpha preservation, and the PNG output are all
 * verified for real rather than mocked.
 */
class StickerImageProcessorTest {

    @Test
    fun downscaleCutOut_largeTransparentPng_cappedAtStickerMax_keepsAlphaAndStaysPng() = runTest {
        val src = transparentPng(1500, 1000)

        val out = StickerImageProcessor.downscaleCutOut(src)

        val size = ImageUtils.getNaturalSize(out)
        assertTrue(
            size.pixelWidth <= StickerImageProcessor.STICKER_MAX_DIM &&
                size.pixelHeight <= StickerImageProcessor.STICKER_MAX_DIM,
            "cut-out must be capped at ${StickerImageProcessor.STICKER_MAX_DIM}px, was ${size.pixelWidth}x${size.pixelHeight}",
        )
        // Long edge maps exactly to the cap; aspect is preserved (1500x1000 -> 512x341).
        assertEquals(StickerImageProcessor.STICKER_MAX_DIM, size.pixelWidth)
        assertEquals(
            "image/png",
            ImageFormatDetector.detectFormat(out),
            "must stay PNG — the Android ImageUtils WebP branch crashes on API 28/29",
        )
        assertTrue(
            ImageUtils.hasNonOpaquePixels(out),
            "transparency must survive the downscale or the sticker loses its cut-out",
        )
        assertTrue(out.size < src.size, "the 512px cut-out should be smaller than the 1500px source")
    }

    @Test
    fun downscaleCutOut_smallImage_isNotUpscaled_andKeepsAlpha() = runTest {
        val src = transparentPng(300, 200)

        val out = StickerImageProcessor.downscaleCutOut(src)

        val size = ImageUtils.getNaturalSize(out)
        assertEquals(300, size.pixelWidth, "must not upscale an already-small cut-out")
        assertEquals(200, size.pixelHeight)
        assertTrue(ImageUtils.hasNonOpaquePixels(out))
    }

    @Test
    fun downscaleCutOut_undecodableBytes_fallsBackToInputUnchanged() = runTest {
        val junk = byteArrayOf(1, 2, 3, 4, 5)

        assertContentEquals(
            junk,
            StickerImageProcessor.downscaleCutOut(junk),
            "a re-encode failure must fall back to the original bytes, never block sending",
        )
    }

    @Test
    fun cropToSubject_centeredOval_cropsToBboxPlusMargin_staysPngWithAlpha() = runTest {
        // 1500x1000 with a centered opaque oval at (w/4, h/4, w/2, h/2) -> bbox ~750x500.
        val src = transparentPng(1500, 1000)

        val out = StickerImageProcessor.cropToSubject(src)

        val size = ImageUtils.getNaturalSize(out)
        // Cropped to roughly the oval bbox (~750x500) + a 4% margin — far smaller than the full frame.
        assertTrue(
            size.pixelWidth < 1500 && size.pixelHeight < 1000,
            "must crop tighter than the full frame, was ${size.pixelWidth}x${size.pixelHeight}",
        )
        assertTrue(
            size.pixelWidth in 700..900 && size.pixelHeight in 450..650,
            "crop should be ~oval bbox + small margin, was ${size.pixelWidth}x${size.pixelHeight}",
        )
        assertEquals(
            "image/png",
            ImageFormatDetector.detectFormat(out),
            "must stay PNG — the Android ImageUtils WebP branch crashes on API 28/29",
        )
        assertTrue(
            ImageUtils.hasNonOpaquePixels(out),
            "the transparent margin must survive so the bubble still drops its backdrop",
        )
    }

    @Test
    fun cropToSubject_fullyTransparent_returnsInputUnchanged() = runTest {
        val blank = blankTransparentPng(400, 300)

        assertContentEquals(
            blank,
            StickerImageProcessor.cropToSubject(blank),
            "a fully transparent image has no subject to crop to — return it unchanged",
        )
    }

    @Test
    fun cropToSubject_undecodableBytes_fallsBackToInputUnchanged() = runTest {
        val junk = byteArrayOf(1, 2, 3, 4, 5)

        assertContentEquals(
            junk,
            StickerImageProcessor.cropToSubject(junk),
            "a decode failure must fall back to the original bytes, never block sticker creation",
        )
    }

    /** A fully transparent PNG of the given size (no subject). */
    private fun blankTransparentPng(width: Int, height: Int): ByteArray {
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val out = ByteArrayOutputStream()
        ImageIO.write(img, "png", out)
        return out.toByteArray()
    }

    /** A PNG with a fully transparent canvas and one opaque blob — content + alpha. */
    private fun transparentPng(width: Int, height: Int): ByteArray {
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.color = Color(220, 40, 40, 255)
        g.fillOval(width / 4, height / 4, width / 2, height / 2)
        g.dispose()
        val out = ByteArrayOutputStream()
        ImageIO.write(img, "png", out)
        return out.toByteArray()
    }
}
