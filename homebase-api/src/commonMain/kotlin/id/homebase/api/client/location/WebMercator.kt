package id.homebase.api.client.location

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

/**
 * Web Mercator / Slippy Map math, shared by the chat location preview
 * (single-tile static map) and the Location history trace canvas.
 *
 * "Unit" coordinates are the continuous world position in 0..1 on both axes
 * (x grows east, y grows south), i.e. tile coordinates divided by 2^zoom.
 */
object WebMercator {

    const val TILE_SIZE_PX = 256

    /** Continuous world position in 0..1 (Slippy Map projection). */
    fun latLonToUnit(lat: Double, lon: Double): Pair<Double, Double> {
        val x = (lon + 180.0) / 360.0
        val latRad = lat * PI / 180.0
        val y = (1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0
        return x to y
    }

    /** Tile index along one axis for a unit coordinate at [zoom], clamped to the grid. */
    fun unitToTile(unit: Double, zoom: Int): Int {
        val n = 1 shl zoom
        return floor(unit * n).toInt().coerceIn(0, n - 1)
    }

    /** Tile X/Y at [zoom] for a lat/lon — the standard Slippy Map formula. */
    fun latLonToTile(lat: Double, lon: Double, zoom: Int): Pair<Int, Int> {
        val (x, y) = latLonToUnit(lat, lon)
        return unitToTile(x, zoom) to unitToTile(y, zoom)
    }

    /** Unit-space bounds of a tile: (minX, minY, maxX, maxY). */
    fun tileToUnitBounds(xTile: Int, yTile: Int, zoom: Int): DoubleArray {
        val n = (1 shl zoom).toDouble()
        return doubleArrayOf(xTile / n, yTile / n, (xTile + 1) / n, (yTile + 1) / n)
    }
}
