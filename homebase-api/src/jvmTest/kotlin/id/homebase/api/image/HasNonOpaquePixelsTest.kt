package id.homebase.api.image

import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorInfo
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Image
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the alpha probe that drives sticker auto-detection. Runs the skiaMain actual
 * (which backs Desktop/iOS/Web) on the JVM. The contract: report `true` only when a
 * sampled pixel is genuinely non-opaque, `false` for fully opaque images, and `false`
 * (never throw) on undecodable bytes — opaque is the safe default so we never strip a
 * real photo's backdrop.
 */
class HasNonOpaquePixelsTest {

    /** Encode a w×h BGRA buffer (alpha provided per pixel) as a PNG. */
    private fun pngWithAlpha(w: Int, h: Int, alphaAt: (x: Int, y: Int) -> Int): ByteArray {
        val info = ImageInfo(
            colorInfo = ColorInfo(ColorType.BGRA_8888, ColorAlphaType.UNPREMUL, ColorSpace.sRGB),
            width = w,
            height = h,
        )
        val rowBytes = w * 4
        val bytes = ByteArray(w * h * 4)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = (y * w + x) * 4
                bytes[i] = 0       // B
                bytes[i + 1] = 0   // G
                bytes[i + 2] = (0xFF).toByte() // R
                bytes[i + 3] = (alphaAt(x, y) and 0xFF).toByte() // A
            }
        }
        val image = Image.makeRaster(info, bytes, rowBytes)
        val data = image.encodeToData(EncodedImageFormat.PNG)
            ?: error("Failed to encode test PNG")
        return data.bytes
    }

    @Test
    fun opaquePng_isReportedOpaque() {
        val bytes = pngWithAlpha(64, 64) { _, _ -> 0xFF }
        assertFalse(ImageUtils.hasNonOpaquePixels(bytes), "Fully-opaque PNG must report false")
    }

    @Test
    fun fullyTransparentPng_isReportedNonOpaque() {
        val bytes = pngWithAlpha(64, 64) { _, _ -> 0x00 }
        assertTrue(ImageUtils.hasNonOpaquePixels(bytes), "Transparent PNG must report true")
    }

    @Test
    fun pngWithOneTransparentCorner_isReportedNonOpaque() {
        // A cut-out sticker often carries transparency only at the corners — the
        // corner-probe guarantees we catch it even if the coarse grid steps over it.
        val bytes = pngWithAlpha(200, 200) { x, y ->
            if (x == 0 && y == 0) 0x00 else 0xFF
        }
        assertTrue(
            ImageUtils.hasNonOpaquePixels(bytes),
            "A single transparent corner must be detected",
        )
    }

    @Test
    fun nearOpaqueFringe_belowThreshold_isReportedOpaque() {
        // alpha 252 (> 250 threshold) models WebP/PNG compression fringe on a near-opaque
        // photo — it must NOT be misdetected as a sticker.
        val bytes = pngWithAlpha(64, 64) { _, _ -> 252 }
        assertFalse(
            ImageUtils.hasNonOpaquePixels(bytes),
            "Near-opaque fringe (alpha 252) must stay below the non-opaque threshold",
        )
    }

    @Test
    fun opaqueJpeg_isReportedOpaque() {
        val bytes = ImageTestHelper.loadImage("red-leaf.jpg")
        assertFalse(ImageUtils.hasNonOpaquePixels(bytes), "Opaque JPEG must report false")
    }

    @Test
    fun realTransparentPngFixture_isReportedNonOpaque() {
        val bytes = ImageTestHelper.loadImage("shirt_transparent.png")
        assertTrue(
            ImageUtils.hasNonOpaquePixels(bytes),
            "A real cut-out PNG fixture must report true",
        )
    }

    @Test
    fun corruptBytes_returnFalse_noThrow() {
        val bytes = ByteArray(128) { it.toByte() }
        assertFalse(ImageUtils.hasNonOpaquePixels(bytes), "Corrupt bytes must report false, not throw")
    }

    @Test
    fun emptyBytes_returnFalse_noThrow() {
        assertFalse(ImageUtils.hasNonOpaquePixels(ByteArray(0)))
    }
}
