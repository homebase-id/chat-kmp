package id.homebase.api.client.location

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Pins [WebMercator.unitToLatLon] as the true inverse of [WebMercator.latLonToUnit] across the
 * Web-Mercator domain (|lat| ≤ 85) — the share-location screen relies on it to turn the panned
 * viewport center back into the coordinates it sends.
 */
class WebMercatorRoundTripTest {

    @Test
    fun unitToLatLonInvertsLatLonToUnit() {
        for (lat in -85..85 step 5) {
            for (lon in -180..175 step 5) {
                val (x, y) = WebMercator.latLonToUnit(lat.toDouble(), lon.toDouble())
                val (latBack, lonBack) = WebMercator.unitToLatLon(x, y)
                assertTrue(abs(latBack - lat) < 1e-9, "lat $lat → $latBack (lon=$lon)")
                assertTrue(abs(lonBack - lon) < 1e-9, "lon $lon → $lonBack (lat=$lat)")
            }
        }
    }

    @Test
    fun unitCornersMapToDomainEdges() {
        val (latTop, lonLeft) = WebMercator.unitToLatLon(0.0, 0.0)
        assertTrue(latTop > 85.0, "top of unit space is the Mercator max latitude, got $latTop")
        assertTrue(abs(lonLeft - (-180.0)) < 1e-9)
        val (latMid, lonMid) = WebMercator.unitToLatLon(0.5, 0.5)
        assertTrue(abs(latMid) < 1e-9 && abs(lonMid) < 1e-9, "unit center is (0,0), got $latMid,$lonMid")
    }
}
