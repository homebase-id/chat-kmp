package id.homebase.core.ui.screens.location.map

import androidx.compose.ui.unit.IntSize
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the over-budget tile truncation in [visibleTileKeys]: when the visible grid exceeds
 * MAX_TILES_PER_VIEW even at the minimum zoom (a portrait world view needs ~64 tiles), the kept
 * tiles must surround the VIEWPORT CENTER — a top-anchored cut rendered only the top band of the
 * world with the rest of the screen bare (the "half a world map" on the #966 share screen's
 * no-fix fallback).
 */
class MapProjectionTruncationTest {

    @Test
    fun overBudgetWorldViewKeepsTilesNearestTheViewportCenter() {
        // Portrait phone fitting the whole world: idealZoom clamps to MIN_TILE_ZOOM and the full
        // grid (64 tiles) exceeds the 24-tile budget → truncation kicks in.
        val vp = MapViewport(centerX = 0.5, centerY = 0.5, unitsPerPx = 1.0 / 900.0)
        val keys = visibleTileKeys(vp, IntSize(1080, 2200))

        assertTrue(keys.isNotEmpty())
        assertTrue(keys.size <= 24, "budget respected, got ${keys.size}")
        val zoom = keys.first().zoom
        val n = 1 shl zoom
        // The four tiles around the viewport center must be kept…
        for (x in (n / 2 - 1)..(n / 2)) {
            for (y in (n / 2 - 1)..(n / 2)) {
                assertTrue(keys.contains(MapTileKey(zoom, x, y)), "missing center tile ($x,$y)")
            }
        }
        // …and the far corner (which the old top-anchored cut kept) must be dropped.
        assertFalse(keys.contains(MapTileKey(zoom, 0, 0)), "corner tile should be dropped")
    }

    @Test
    fun withinBudgetGridIsUntouched() {
        // A city-level viewport fits comfortably in the budget — full grid, no truncation.
        val vp = MapViewport(centerX = 0.53, centerY = 0.33, unitsPerPx = 1e-6)
        val keys = visibleTileKeys(vp, IntSize(1080, 2200))
        assertTrue(keys.isNotEmpty())
        assertTrue(keys.size <= 24)
        // Grid is rectangular and contiguous (no holes from sorting).
        val xs = keys.map { it.x }.distinct().sorted()
        val ys = keys.map { it.y }.distinct().sorted()
        assertTrue(xs == (xs.first()..xs.last()).toList(), "x range contiguous")
        assertTrue(ys == (ys.first()..ys.last()).toList(), "y range contiguous")
        assertTrue(keys.size == xs.size * ys.size, "full rectangular grid")
    }
}
