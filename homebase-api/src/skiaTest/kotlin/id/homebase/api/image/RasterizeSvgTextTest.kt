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

    private suspend fun render(inner: String): IntArray {
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" width="200" height="200">
            <rect width="200" height="200" fill="#ffffff"/>$inner</svg>"""
        val result = ImageUtils.rasterizeSvg(svg.encodeToByteArray(), 200, ImageFormat.PNG, 100)
        return ImageUtils.decodeToArgb(result.bytes)!!.pixels
    }

    private fun text(attrs: String) = """<text x="100" y="150" font-size="160" text-anchor="middle" $attrs>M</text>"""

    private suspend fun assertNoText(inner: String) {
        val inked = render(inner).count { (it and 0xFFFFFF) != 0xFFFFFF }
        assertTrue(inked == 0, "expected hidden text, found $inked inked pixels")
    }

    private suspend fun dominantInk(inner: String): Int {
        val counts = HashMap<Int, Int>()
        for (c in render(inner)) if ((c and 0xFFFFFF) != 0xFFFFFF) counts[c and 0xFFFFFF] = (counts[c and 0xFFFFFF] ?: 0) + 1
        return counts.maxByOrNull { it.value }?.key ?: -1
    }

    private suspend fun assertInk(inner: String, rgb: Int) {
        val got = dominantInk(inner)
        fun near(shift: Int) = kotlin.math.abs(((got shr shift) and 0xFF) - ((rgb shr shift) and 0xFF)) <= 3
        assertTrue(got != -1 && near(16) && near(8) && near(0), "expected #${rgb.toString(16)}, got #${got.toString(16)} for $inner")
    }

    @Test
    fun textInHiddenContainersIsNotDrawn() = runTest {
        for (tag in listOf("defs", "symbol", "clipPath", "mask", "pattern")) {
            assertNoText("<$tag id=\"a\">${text("fill=\"#000000\"")}</$tag>")
        }
    }

    @Test
    fun textInCommentsAndCdataIsNotDrawn() = runTest {
        assertNoText("<!-- ${text("fill=\"#000000\"")} -->")
        assertNoText("<style><![CDATA[ ${text("fill=\"#000000\"")} ]]></style>")
    }

    @Test
    fun textWithDisplayNoneOrHiddenVisibilityIsNotDrawn() = runTest {
        assertNoText(text("display=\"none\""))
        assertNoText(text("visibility=\"hidden\""))
        assertNoText("<g display=\"none\"><g>${text("")}</g></g>")
        assertNoText("<g visibility=\"hidden\">${text("")}</g>")
    }

    @Test
    fun visibleTextAfterHiddenContainerIsStillDrawn() = runTest {
        assertInk("<defs>${text("")}</defs><g visibility=\"hidden\"><text visibility=\"visible\" x=\"100\" y=\"150\" font-size=\"160\" text-anchor=\"middle\" fill=\"#0000ff\">M</text></g>", 0x0000FF)
    }

    @Test
    fun colourForms() = runTest {
        val cases = listOf(
            "#f00" to 0xFF0000, "#00ff00" to 0x00FF00, "#0000ffff" to 0x0000FF,
            "#f00f" to 0xFF0000, "rgb(255, 0, 0)" to 0xFF0000, "rgb(0%,100%,0%)" to 0x00FF00,
            "rgba(0,0,255,1)" to 0x0000FF, "RED" to 0xFF0000, "rebeccapurple" to 0x663399,
            "cornflowerblue" to 0x6495ED, "lightgoldenrodyellow" to 0xFAFAD2,
            "#ff000080" to 0xFF7F7F, "#f008" to 0xFF7777, "rgba(255,0,0,0.5)" to 0xFF7F7F,
            "rgb(255 0 0 / 50%)" to 0xFF7F7F, "bogus" to 0x000000,
        )
        for ((fill, rgb) in cases) assertInk(text("fill=\"$fill\""), rgb)
    }

    @Test
    fun noneAndTransparentDrawNothing() = runTest {
        assertNoText(text("fill=\"none\""))
        assertNoText(text("fill=\"transparent\""))
        assertNoText(text("fill=\"#ff000000\""))
    }

    @Test
    fun currentColorResolvesFromNearestAncestorOrBlack() = runTest {
        assertInk(text("fill=\"currentColor\""), 0x000000)
        assertInk("<g color=\"#00ff00\">${text("fill=\"currentColor\"")}</g>", 0x00FF00)
        assertInk("<g color=\"#00ff00\"><g color=\"red\">${text("fill=\"currentcolor\"")}</g></g>", 0xFF0000)
        assertInk(text("fill=\"currentColor\" color=\"blue\""), 0x0000FF)
    }

    @Test
    fun opacityAttributesBlendTheFill() = runTest {
        assertInk(text("fill=\"#ff0000\" fill-opacity=\"0.5\""), 0xFF7F7F)
        assertInk(text("fill=\"#ff0000\" opacity=\"50%\""), 0xFF7F7F)
        assertInk("<g opacity=\"0.5\">${text("fill=\"#ff0000\"")}</g>", 0xFF7F7F)
        assertNoText(text("fill=\"#ff0000\" opacity=\"0\""))
    }
}
