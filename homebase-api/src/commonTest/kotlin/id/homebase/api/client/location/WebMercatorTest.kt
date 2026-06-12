package id.homebase.api.client.location

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WebMercatorTest {

    @Test
    fun originOfTheWorld() {
        val (x, y) = WebMercator.latLonToUnit(0.0, 0.0)
        assertEquals(0.5, x, 1e-9)
        assertEquals(0.5, y, 1e-9)
    }

    @Test
    fun knownCityTileMatchesSlippyMapReference() {
        // Berlin Alexanderplatz at zoom 15 — reference values from the
        // OpenStreetMap slippy-map tilenames formula.
        val (x, y) = WebMercator.latLonToTile(52.5219, 13.4132, 15)
        assertEquals(17604, x)
        assertEquals(10746, y)
    }

    @Test
    fun unitGrowsEastAndSouth() {
        val (xWest, _) = WebMercator.latLonToUnit(0.0, -120.0)
        val (xEast, _) = WebMercator.latLonToUnit(0.0, 120.0)
        assertTrue(xWest < 0.5 && xEast > 0.5)

        val (_, yNorth) = WebMercator.latLonToUnit(60.0, 0.0)
        val (_, ySouth) = WebMercator.latLonToUnit(-60.0, 0.0)
        assertTrue(yNorth < 0.5 && ySouth > 0.5)
    }

    @Test
    fun tileBoundsContainTheOriginatingPoint() {
        val lat = 55.6761; val lon = 12.5683 // Copenhagen
        for (zoom in listOf(3, 10, 16)) {
            val (tx, ty) = WebMercator.latLonToTile(lat, lon, zoom)
            val b = WebMercator.tileToUnitBounds(tx, ty, zoom)
            val (ux, uy) = WebMercator.latLonToUnit(lat, lon)
            assertTrue(ux >= b[0] && ux < b[2], "x out of bounds at z$zoom")
            assertTrue(uy >= b[1] && uy < b[3], "y out of bounds at z$zoom")
        }
    }

    @Test
    fun unitToTileClampsToGrid() {
        assertEquals(0, WebMercator.unitToTile(-0.1, 4))
        assertEquals(15, WebMercator.unitToTile(1.1, 4))
    }
}
