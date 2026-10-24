package id.homebase.api.image

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

class RasterizeSvgTextTest {

    private class Box(val count: Int, val minX: Float, val maxX: Float, val minY: Float, val maxY: Float)

    private suspend fun lightPixelBox(svg: String): Box {
        val result = ImageUtils.rasterizeSvg(svg.encodeToByteArray(), 200, ImageFormat.PNG, 100)
        val img = ImageUtils.decodeToArgb(result.bytes)!!
        var count = 0
        var minX = Int.MAX_VALUE; var maxX = -1; var minY = Int.MAX_VALUE; var maxY = -1
        for (y in 0 until img.height) for (x in 0 until img.width) {
            val c = img.pixels[y * img.width + x]
            if ((c ushr 24) > 200 && ((c shr 16) and 0xFF) > 200) {
                count++
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
        }
        return Box(count, minX.toFloat() / img.width, (maxX + 1).toFloat() / img.width,
            minY.toFloat() / img.height, (maxY + 1).toFloat() / img.height)
    }

    private fun assertInside(box: Box, x0: Float, x1: Float, y0: Float, y1: Float) {
        assertTrue(box.count > 100, "expected text pixels, found ${box.count}")
        assertTrue(
            box.minX >= x0 && box.maxX <= x1 && box.minY >= y0 && box.maxY <= y1,
            "text box x=${box.minX}..${box.maxX} y=${box.minY}..${box.maxY} outside $x0..$x1 / $y0..$y1",
        )
    }

    @Test
    fun textIsDrawnWhenViewBoxEqualsSize() = runTest {
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" width="200" height="200" viewBox="0 0 200 200">
            <rect width="200" height="200" fill="#112233"/>
            <text x="100" y="125" font-size="80" fill="#ffffff" text-anchor="middle" font-family="sans-serif">ME</text></svg>"""
        assertInside(lightPixelBox(svg), 0.1f, 0.9f, 0.2f, 0.8f)
    }

    @Test
    fun textFollowsViewBoxOnlySvg() = runTest {
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64">
            <rect width="64" height="64" fill="#112233"/>
            <text x="32" y="44" font-size="32" fill="#ffffff" text-anchor="middle" font-family="sans-serif">M</text></svg>"""
        assertInside(lightPixelBox(svg), 0.2f, 0.8f, 0.25f, 0.75f)
    }

    @Test
    fun textFollowsViewBoxWhenSizeDiffers() = runTest {
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" width="200" height="200" viewBox="0 0 100 100">
            <rect width="100" height="100" fill="#112233"/>
            <text x="50" y="70" font-size="50" fill="#ffffff" text-anchor="middle" font-family="sans-serif">M</text></svg>"""
        assertInside(lightPixelBox(svg), 0.2f, 0.8f, 0.2f, 0.8f)
    }

    @Test
    fun textIsLetterboxedLikeTheShapes() = runTest {
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" width="200" height="200" viewBox="0 0 100 50">
            <rect width="100" height="50" fill="#112233"/>
            <text x="50" y="35" font-size="30" fill="#ffffff" text-anchor="middle" font-family="sans-serif">M</text></svg>"""
        assertInside(lightPixelBox(svg), 0.3f, 0.7f, 0.3f, 0.7f)
    }

    @Test
    fun textFollowsPreserveAspectRatioAlign() = runTest {
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" width="200" height="200" viewBox="0 0 100 50" preserveAspectRatio="xMidYMin meet">
            <rect width="100" height="50" fill="#112233"/>
            <text x="50" y="35" font-size="30" fill="#ffffff" text-anchor="middle" font-family="sans-serif">M</text></svg>"""
        assertInside(lightPixelBox(svg), 0.3f, 0.7f, 0.0f, 0.5f)
    }

    @Test
    fun unfilledAndSelfClosingTextDrawsNothing() = runTest {
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" width="200" height="200">
            <rect width="200" height="200" fill="#ffffff"/>
            <text x="10" y="100" font-size="80"/>
            <text x="100" y="125" font-size="80" fill="none" stroke="#000000" text-anchor="middle">ME</text></svg>"""
        val result = ImageUtils.rasterizeSvg(svg.encodeToByteArray(), 200, ImageFormat.PNG, 100)
        val img = ImageUtils.decodeToArgb(result.bytes)!!
        val dark = img.pixels.count { ((it shr 16) and 0xFF) < 100 }
        assertTrue(dark == 0, "expected no glyph pixels, found $dark")
    }
}
