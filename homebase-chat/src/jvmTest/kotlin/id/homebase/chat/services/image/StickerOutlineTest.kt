package id.homebase.chat.services.image

import id.homebase.api.image.ArgbImage
import id.homebase.api.image.ImageUtils
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StickerOutlineTest {
    private fun png(size: Int, set: (IntArray) -> Unit): ByteArray {
        val px = IntArray(size * size) { 0x00000000 }
        set(px)
        return ImageUtils.encodeArgbToPng(ArgbImage(px, size, size))
    }

    @Test fun pads_canvas_and_rings_subject_in_white() = runTest {
        val size = 5; val r = 1
        val src = png(size) { it[2 * size + 2] = 0xFFFF0000.toInt() } // opaque red center
        val img = ImageUtils.decodeToArgb(StickerImageProcessor.addWhiteOutline(src, radiusPx = r))!!
        assertEquals(size + 2 * r, img.width)   // canvas padded by r
        assertEquals(size + 2 * r, img.height)
        val pw = img.width
        val center = (2 + r) * pw + (2 + r)
        assertEquals(0xFF0000, img.pixels[center] and 0xFFFFFF)            // subject preserved
        assertEquals(0xFF, img.pixels[center] ushr 24 and 0xFF)
        val up = center - pw
        assertEquals(0xFF, img.pixels[up] ushr 24 and 0xFF)               // orthogonal neighbour = opaque white
        assertEquals(0xFFFFFF, img.pixels[up] and 0xFFFFFF)
        assertEquals(0x00, img.pixels[0] ushr 24 and 0xFF)               // far corner transparent
    }

    @Test fun fully_transparent_stays_transparent() = runTest {
        val img = ImageUtils.decodeToArgb(StickerImageProcessor.addWhiteOutline(png(4) {}))!!
        assertTrue(img.pixels.all { (it ushr 24 and 0xFF) == 0 })
    }

    @Test fun decode_failure_returns_input_unchanged() = runTest {
        val garbage = byteArrayOf(1, 2, 3, 4)
        assertTrue(StickerImageProcessor.addWhiteOutline(garbage, radiusPx = 1).contentEquals(garbage))
    }

    @Test fun partial_alpha_subject_composites_over_white_halo() = runTest {
        val src = png(3) { it[1 * 3 + 1] = 0x80FF0000.toInt() } // 50% red center
        val img = ImageUtils.decodeToArgb(StickerImageProcessor.addWhiteOutline(src, radiusPx = 1))!!
        val pw = img.width
        val c = (1 + 1) * pw + (1 + 1)
        assertEquals(0xFF, img.pixels[c] ushr 24 and 0xFF)               // opaque after compositing over white
        assertEquals(0xFF7F7F, img.pixels[c] and 0xFFFFFF)               // 50% red over white = pink
    }

    @Test fun radius_zero_subject_only_no_ring_no_padding() = runTest {
        val src = png(3) { it[1 * 3 + 1] = 0xFFFF0000.toInt() }
        val img = ImageUtils.decodeToArgb(StickerImageProcessor.addWhiteOutline(src, radiusPx = 0))!!
        assertEquals(3, img.width)                                        // no padding at r=0
        val pw = img.width
        assertEquals(0xFF, img.pixels[1 * pw + 1] ushr 24 and 0xFF)       // subject opaque
        assertEquals(0x00, img.pixels[1 * pw + 0] ushr 24 and 0xFF)       // neighbour transparent (no ring)
    }

    @Test fun border_touching_subject_halo_extends_into_padding() = runTest {
        val size = 3; val r = 1
        val src = png(size) { it[0] = 0xFFFF0000.toInt() } // subject at top-left corner
        val img = ImageUtils.decodeToArgb(StickerImageProcessor.addWhiteOutline(src, radiusPx = r))!!
        val pw = img.width
        assertEquals(size + 2 * r, pw)
        // subject is at padded (r, r); its orthogonal neighbour in the top padding row is white.
        val up = (r - 1) * pw + r
        assertEquals(0xFF, img.pixels[up] ushr 24 and 0xFF)
        assertEquals(0xFFFFFF, img.pixels[up] and 0xFFFFFF)
    }
}
