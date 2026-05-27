package id.homebase.core.media.subsample

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TileGridTest {

    @Test
    fun `sampleSizeForZoom returns power of 2`() {
        assertEquals(8, TileGrid.sampleSizeForZoom(1f))
        assertEquals(4, TileGrid.sampleSizeForZoom(2f))
        assertEquals(2, TileGrid.sampleSizeForZoom(4f))
        assertEquals(1, TileGrid.sampleSizeForZoom(5f))
        assertEquals(1, TileGrid.sampleSizeForZoom(10f))
    }

    @Test
    fun `sampleSizeForZoom at boundary`() {
        assertEquals(4, TileGrid.sampleSizeForZoom(1.5f))
        assertEquals(2, TileGrid.sampleSizeForZoom(3f))
    }

    @Test
    fun `tileRect clamps to image bounds`() {
        val rect = TileGrid.tileRect(col = 7, row = 5, tileSize = 512, imageWidth = 4000, imageHeight = 3000)
        assertEquals(3584, rect.left)
        assertEquals(2560, rect.top)
        assertEquals(4000, rect.right)
        assertEquals(3000, rect.bottom)
    }

    @Test
    fun `tileRect at origin`() {
        val rect = TileGrid.tileRect(col = 0, row = 0, tileSize = 512, imageWidth = 4000, imageHeight = 3000)
        assertEquals(IntRect(0, 0, 512, 512), rect)
    }

    @Test
    fun `visibleTiles returns tiles intersecting viewport with preload margin`() {
        val tiles = TileGrid.visibleTiles(
            viewport = IntRect(0, 0, 512, 512),
            imageSize = IntSize(2048, 2048),
            tileSize = 512,
            sampleSize = 1,
        )
        assertTrue(tiles.any { it.col == 0 && it.row == 0 })
        // viewport covers 1 tile; +1 preload margin extends to col/row 2, so 3x3 = 9
        assertEquals(9, tiles.size)
    }

    @Test
    fun `visibleTiles with border preloads adjacent`() {
        val tiles = TileGrid.visibleTiles(
            viewport = IntRect(512, 512, 1024, 1024),
            imageSize = IntSize(4096, 4096),
            tileSize = 512,
            sampleSize = 1,
        )
        // viewport spans col 1..1, row 1..1; preload extends to col 0..3, row 0..3 => 4x4=16
        assertEquals(16, tiles.size)
        assertTrue(tiles.any { it.col == 1 && it.row == 1 })
    }

    @Test
    fun `visibleTiles does not exceed grid bounds`() {
        val tiles = TileGrid.visibleTiles(
            viewport = IntRect(0, 0, 1024, 1024),
            imageSize = IntSize(1024, 1024),
            tileSize = 512,
            sampleSize = 1,
        )
        assertTrue(tiles.all { it.col in 0..1 && it.row in 0..1 })
    }

    @Test
    fun `gridSize calculates correct tile count`() {
        val size = TileGrid.gridSize(imageWidth = 4000, imageHeight = 3000, tileSize = 512)
        assertEquals(8, size.width)
        assertEquals(6, size.height)
    }

    @Test
    fun `calculateViewport at zoom 1x returns full image`() {
        val viewport = TileGrid.calculateViewport(
            scale = 1f,
            offset = Offset.Zero,
            imageSize = IntSize(4000, 3000),
            screenSize = IntSize(1080, 1920),
        )
        assertEquals(IntRect(0, 0, 4000, 3000), viewport)
    }

    @Test
    fun `calculateViewport at zoom 2x returns half-sized viewport`() {
        val viewport = TileGrid.calculateViewport(
            scale = 2f,
            offset = Offset.Zero,
            imageSize = IntSize(4000, 3000),
            screenSize = IntSize(1080, 810),
        )
        assertEquals(2000, viewport.width)
        assertEquals(1500, viewport.height)
    }

    @Test
    fun `calculateViewport clamps to image bounds`() {
        val viewport = TileGrid.calculateViewport(
            scale = 2f,
            offset = Offset(-5000f, -5000f),
            imageSize = IntSize(4000, 3000),
            screenSize = IntSize(1080, 810),
        )
        assertTrue(viewport.left >= 0)
        assertTrue(viewport.top >= 0)
        assertTrue(viewport.right <= 4000)
        assertTrue(viewport.bottom <= 3000)
    }

    @Test
    fun `calculateViewport with offset shifts viewport`() {
        val viewport = TileGrid.calculateViewport(
            scale = 2f,
            offset = Offset(500f, 300f),
            imageSize = IntSize(4000, 3000),
            screenSize = IntSize(1080, 810),
        )
        val centered = TileGrid.calculateViewport(
            scale = 2f,
            offset = Offset.Zero,
            imageSize = IntSize(4000, 3000),
            screenSize = IntSize(1080, 810),
        )
        assertTrue(viewport.left != centered.left || viewport.top != centered.top)
    }

    @Test
    fun `calculateViewport offset scales correctly with screen-to-image ratio`() {
        val screenSize = IntSize(1080, 810)
        val imageSize = IntSize(4000, 3000)
        val viewport = TileGrid.calculateViewport(
            scale = 3f,
            offset = Offset(540f, 0f),
            imageSize = imageSize,
            screenSize = screenSize,
        )
        // offset 540 screen px = 540 * 4000 / (3 * 1080) = 666 image px shift
        // center = 2000 - 666 = 1334
        // viewport width = 4000/3 = 1333
        // left = 1334 - 666 = 668 (approx)
        assertTrue(viewport.left > 600, "Left edge should shift significantly: ${viewport.left}")
        assertTrue(viewport.left < 750, "Left edge should not overshoot: ${viewport.left}")
    }

    @Test
    fun `calculateViewport with zero screen size returns full image`() {
        val viewport = TileGrid.calculateViewport(
            scale = 3f,
            offset = Offset(100f, 100f),
            imageSize = IntSize(4000, 3000),
            screenSize = IntSize(0, 0),
        )
        assertEquals(IntRect(0, 0, 4000, 3000), viewport)
    }
}
