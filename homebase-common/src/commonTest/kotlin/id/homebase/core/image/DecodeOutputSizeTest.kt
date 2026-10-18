package id.homebase.core.image

import coil3.size.Precision
import coil3.size.Scale
import coil3.size.Size
import kotlin.test.Test
import kotlin.test.assertEquals

class DecodeOutputSizeTest {

    private fun out(w: Int, h: Int, size: Size, scale: Scale = Scale.FILL, precision: Precision = Precision.INEXACT) =
        decodeOutputSize(w, h, size, scale, maxBitmapSize = Size(4096, 4096), precision = precision)

    @Test
    fun `12MP photo in a 60dp strip thumbnail decodes at thumbnail size`() {
        assertEquals(252 to 189, out(4032, 3024, Size(189, 189)))
    }

    @Test
    fun `12MP photo in a full-width page decodes at page width`() {
        assertEquals(1008 to 756, out(4032, 3024, Size(1008, 2000), scale = Scale.FIT))
    }

    @Test
    fun `original size is capped by maxBitmapSize`() {
        assertEquals(4096 to 3072, out(8192, 6144, Size.ORIGINAL, scale = Scale.FIT))
    }

    @Test
    fun `inexact never upscales a small source`() {
        assertEquals(100 to 80, out(100, 80, Size(1000, 1000)))
    }

    @Test
    fun `exact upscales to fill the target`() {
        assertEquals(1000 to 800, out(100, 80, Size(1000, 800), precision = Precision.EXACT))
    }
}
