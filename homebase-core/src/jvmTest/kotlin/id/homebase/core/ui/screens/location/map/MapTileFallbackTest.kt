package id.homebase.core.ui.screens.location.map

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MapTileFallbackTest {

    private fun loaded(vararg keys: MapTileKey): (MapTileKey) -> Int? = { if (it in keys) 256 else null }

    @Test
    fun parentSuppliesTheQuadrantOfTheMissingTile() {
        val parent = MapTileKey(9, 100, 200)
        val fallback = ancestorTile(MapTileKey(10, 201, 400), loaded(parent))
        assertEquals(AncestorTile(parent, IntOffset(128, 0), IntSize(128, 128)), fallback)
    }

    @Test
    fun grandparentSuppliesTheSixteenthOfTheMissingTile() {
        val grandparent = MapTileKey(8, 50, 100)
        val fallback = ancestorTile(MapTileKey(10, 203, 401), loaded(grandparent))
        assertEquals(AncestorTile(grandparent, IntOffset(192, 64), IntSize(64, 64)), fallback)
    }

    @Test
    fun nearestLoadedAncestorWins() {
        val parent = MapTileKey(9, 100, 200)
        val fallback = ancestorTile(MapTileKey(10, 200, 401), loaded(parent, MapTileKey(8, 50, 100)))
        assertEquals(AncestorTile(parent, IntOffset(0, 128), IntSize(128, 128)), fallback)
    }

    @Test
    fun cropScalesWithTheBitmapSize() {
        val fallback = ancestorTile(MapTileKey(10, 201, 401)) { if (it == MapTileKey(9, 100, 200)) 512 else null }
        assertEquals(IntOffset(256, 256), fallback?.srcOffset)
        assertEquals(IntSize(256, 256), fallback?.srcSize)
    }

    @Test
    fun ancestorsBeyondTheDepthLimitAreIgnored() {
        assertNull(ancestorTile(MapTileKey(10, 512, 512), loaded(MapTileKey(5, 16, 16))))
    }

    @Test
    fun noAncestorAboveTheWorldTile() {
        assertNull(ancestorTile(MapTileKey(0, 0, 0)) { 256 })
    }

    @Test
    fun childrenCoverTheTileQuadrants() {
        assertEquals(
            listOf(MapTileKey(4, 6, 10), MapTileKey(4, 7, 10), MapTileKey(4, 6, 11), MapTileKey(4, 7, 11)),
            MapTileKey(3, 3, 5).children(),
        )
    }
}
