package id.homebase.api.image

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ImageUtilsArgbTest {
    @Test fun argb_round_trips_through_png() {
        val w = 2; val h = 2
        val src = intArrayOf(
            0xFFFF0000.toInt(), 0x80000000.toInt(),
            0x00000000,         0xFF00FF00.toInt(),
        )
        val png = ImageUtils.encodeArgbToPng(ArgbImage(src, w, h))
        assertTrue(png.isNotEmpty())

        val decoded = ImageUtils.decodeToArgb(png)
        assertNotNull(decoded)
        assertEquals(w, decoded.width)
        assertEquals(h, decoded.height)
        assertEquals(0x00, decoded.pixels[2] ushr 24 and 0xFF)
        assertEquals(0xFF, decoded.pixels[0] ushr 24 and 0xFF)
        assertEquals(0xFF0000, decoded.pixels[0] and 0xFFFFFF)
        assertEquals(0x00FF00, decoded.pixels[3] and 0xFFFFFF)
    }

    @Test fun argb_round_trips_partial_alpha_and_transparent_rgb_exactly() {
        val w = 4; val h = 1
        val src = intArrayOf(
            0x40DDEEFF.toInt(),  // partial alpha — drifts under premul round-trip
            0x00778899,          // alpha 0 but RGB present — wiped to 0x00000000 under premul
            0x80445566.toInt(),  // half alpha
            0xFF010203.toInt(),  // opaque
        )
        val png = ImageUtils.encodeArgbToPng(ArgbImage(src, w, h))
        val decoded = ImageUtils.decodeToArgb(png)!!
        for (i in src.indices) {
            kotlin.test.assertEquals(
                src[i].toUInt().toString(16), decoded.pixels[i].toUInt().toString(16),
                "pixel $i must round-trip exactly (straight/unpremultiplied alpha)",
            )
        }
    }
}
