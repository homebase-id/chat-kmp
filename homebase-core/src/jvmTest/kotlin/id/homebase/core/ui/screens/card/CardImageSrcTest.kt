package id.homebase.core.ui.screens.card

import id.homebase.api.image.ArgbImage
import id.homebase.api.image.ImageSize
import id.homebase.api.image.ImageUtils
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalEncodingApi::class)
class CardImageSrcTest {

    private fun png(width: Int, height: Int): ByteArray =
        ImageUtils.encodeArgbToPng(ArgbImage(IntArray(width * height) { 0xFF3366CC.toInt() }, width, height))

    private suspend fun encodedSize(width: Int, height: Int, maxEdge: Int = CARD_IMAGE_MAX_EDGE): ImageSize {
        val src = assertNotNull(cardImageSrc(png(width, height), maxEdge))
        val prefix = "data:image/jpeg;base64,"
        assertTrue(src.startsWith(prefix), src.take(40))
        val jpeg = Base64.decode(src.removePrefix(prefix))
        assertEquals(listOf(0xFF, 0xD8, 0xFF), jpeg.take(3).map { it.toInt() and 0xFF }, "JPEG magic")
        return ImageUtils.getNaturalSize(jpeg)
    }

    @Test
    fun landscapeLongEdgeIsCappedAt1080() = runTest {
        assertEquals(ImageSize(1080, 540), encodedSize(2400, 1200))
    }

    @Test
    fun portraitLongEdgeIsCappedAt1080() = runTest {
        assertEquals(ImageSize(360, 1080), encodedSize(1000, 3000))
    }

    @Test
    fun aPostThumbnailIsCappedAtItsOwnEdge() = runTest {
        assertEquals(ImageSize(400, 225), encodedSize(1600, 900, maxEdge = CARD_POST_IMAGE_MAX_EDGE))
    }

    @Test
    fun smallImagesAreNotUpscaled() = runTest {
        assertEquals(ImageSize(300, 200), encodedSize(300, 200))
    }

    @Test
    fun svgPassesThroughUnchangedAsAnSvgDataUrl() = runTest {
        val svgs = listOf(
            "<svg xmlns='http://www.w3.org/2000/svg' width='1200' height='400'><rect width='1200' height='400'/></svg>",
            "﻿<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<!-- header -->\n<svg xmlns=\"http://www.w3.org/2000/svg\"/>",
        )
        svgs.forEach { svg ->
            val bytes = svg.encodeToByteArray()
            assertEquals("data:image/svg+xml;base64," + Base64.encode(bytes), cardImageSrc(bytes))
        }
    }

    @Test
    fun xmlThatIsNotSvgIsNotPassedThrough() = runTest {
        assertNull(cardImageSrc("<?xml version=\"1.0\"?><note>not an image</note>".encodeToByteArray()))
    }

    @Test
    fun undecodableBytesReturnNullWithoutThrowing() = runTest {
        assertNull(cardImageSrc("not an image".encodeToByteArray()))
        assertNull(cardImageSrc(ByteArray(0)))
    }
}
