package id.homebase.api.image

import kotlinx.coroutines.test.runTest
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Image
import kotlin.test.Test
import kotlin.test.assertTrue

class RasterizeSvgTextTest {

    private suspend fun lightPixelsOnDarkBackground(svg: String): Int {
        val result = ImageUtils.rasterizeSvg(svg.encodeToByteArray(), 200, ImageFormat.PNG, 100)
        val bitmap = Bitmap.makeFromImage(Image.makeFromEncoded(result.bytes))
        var light = 0
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
            val c = bitmap.getColor(x, y)
            val r = (c shr 16) and 0xFF
            if (r > 200) light++
        }
        return light
    }

    @Test
    fun avatarInitialsTextIsDrawn() = runTest {
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" width="200" height="200" viewBox="0 0 200 200">
            <rect width="200" height="200" fill="#112233"/>
            <text x="100" y="125" font-size="80" fill="#ffffff" text-anchor="middle" font-family="sans-serif">ME</text></svg>"""
        assertTrue(lightPixelsOnDarkBackground(svg) > 200, "expected text pixels, found none")
    }
}
