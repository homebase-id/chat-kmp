package id.homebase.api.image

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ImageIoDecodeEdgeTest {

    @Test
    fun a48MpPhotoDecodesInFullForHd() {
        assertEquals(8064, imageIoDecodeEdge(ImageSize(8064, 6048), 4096))
    }

    @Test
    fun imageIoHalvesWhereTheOutputIsAQuarterOfThePhotoOrLess() {
        assertEquals(4096, imageIoDecodeEdge(ImageSize(8064, 6048), 2048))
    }

    @Test
    fun overTheGuardImageIoStillShrinksAtLeast2xOrNotAtAll() {
        val sizes = listOf(ImageSize(12000, 9000) to 4096, ImageSize(12000, 9000) to 7000, ImageSize(20000, 15000) to 9000)
        for ((size, target) in sizes) {
            val longEdge = maxOf(size.pixelWidth, size.pixelHeight)
            val edge = imageIoDecodeEdge(size, target)
            assertTrue(edge == longEdge || edge <= longEdge / 2, "$size -> $target decodes at $edge")
            assertTrue(edge >= target, "$size -> $target decodes at $edge, under the output")
        }
    }

    @Test
    fun theGuardShrinksA600MpDecodeToAbout100Mp() {
        assertEquals(12248, imageIoDecodeEdge(ImageSize(30000, 20000), 9000))
    }
}
