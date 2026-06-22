package id.homebase.core.ui.screens.location.map

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import id.homebase.api.client.location.WebMercator
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Pure viewport/tile math shared by every map surface (History traces, the dashboard preview, Find
 * Device, the live-location map). Extracted verbatim from the former `LocationTraceCanvas` privates
 * so all surfaces share one zoom/pan/tile heuristic. No Compose state, no side effects — trivially
 * testable.
 *
 * Coordinates are Web-Mercator **unit space** (0..1 world), the same space [WebMercator.latLonToUnit]
 * produces.
 */

/**
 * Viewport over unit space: a center point plus the unit-width of one screen pixel. Pure data so
 * gestures and tile math share it.
 */
internal data class MapViewport(val centerX: Double, val centerY: Double, val unitsPerPx: Double)

internal data class MapTileKey(val zoom: Int, val x: Int, val y: Int)

/** Project a unit-space point to screen pixels for a canvas of [widthPx] x [heightPx]. */
internal fun MapViewport.toPx(ux: Double, uy: Double, widthPx: Float, heightPx: Float): Offset =
    Offset(
        x = ((ux - centerX) / unitsPerPx + widthPx / 2.0).toFloat(),
        y = ((uy - centerY) / unitsPerPx + heightPx / 2.0).toFloat(),
    )

/**
 * Fit [bbox] (unit-space `[minX,minY,maxX,maxY]`) into [canvasSize] with padding. Returns null until
 * there's both data and a measured canvas. A zero-span bbox (a single point) is widened to
 * [MIN_FIT_SPAN_UNITS] so one point lands at a sensible city-block zoom instead of infinite zoom.
 */
internal fun fitViewport(bbox: DoubleArray?, canvasSize: IntSize): MapViewport? {
    if (bbox == null || canvasSize.width == 0 || canvasSize.height == 0) return null
    val minX = bbox[0]; val minY = bbox[1]; val maxX = bbox[2]; val maxY = bbox[3]
    val spanX = max(maxX - minX, MIN_FIT_SPAN_UNITS)
    val spanY = max(maxY - minY, MIN_FIT_SPAN_UNITS)
    val unitsPerPx = max(
        spanX * FIT_PADDING / canvasSize.width,
        spanY * FIT_PADDING / canvasSize.height,
    ).coerceIn(MIN_UNITS_PER_PX, MAX_UNITS_PER_PX)
    return MapViewport(
        centerX = (minX + maxX) / 2,
        centerY = (minY + maxY) / 2,
        unitsPerPx = unitsPerPx,
    )
}

/**
 * The tile keys covering the current viewport. Starts at the zoom where one 256px-native tile renders
 * ~1:1, then steps DOWN until the full visible grid fits [MAX_TILES_PER_VIEW] — a portrait phone at
 * 1:1 needs ~30-40 tiles, so coverage beats crispness: coarser tiles are upscaled but the whole view
 * gets a basemap (truncating the grid instead left the bottom of the screen bare).
 */
internal fun visibleTileKeys(vp: MapViewport, canvasSize: IntSize): List<MapTileKey> {
    val idealZoom = (-ln(vp.unitsPerPx * WebMercator.TILE_SIZE_PX) / ln(2.0))
        .roundToInt().coerceIn(MIN_TILE_ZOOM, MAX_TILE_ZOOM)
    val halfW = canvasSize.width / 2.0 * vp.unitsPerPx
    val halfH = canvasSize.height / 2.0 * vp.unitsPerPx
    for (zoom in idealZoom downTo MIN_TILE_ZOOM) {
        val n = 1 shl zoom
        val x0 = floor((vp.centerX - halfW) * n).toInt().coerceIn(0, n - 1)
        val x1 = ceil((vp.centerX + halfW) * n).toInt().coerceIn(0, n - 1)
        val y0 = floor((vp.centerY - halfH) * n).toInt().coerceIn(0, n - 1)
        val y1 = ceil((vp.centerY + halfH) * n).toInt().coerceIn(0, n - 1)
        val count = (x1 - x0 + 1) * (y1 - y0 + 1)
        if (count > MAX_TILES_PER_VIEW && zoom > MIN_TILE_ZOOM) continue
        return buildList {
            for (y in y0..y1) {
                for (x in x0..x1) {
                    add(MapTileKey(zoom, x, y))
                    if (size >= MAX_TILES_PER_VIEW) return@buildList
                }
            }
        }
    }
    return emptyList()
}

internal const val MIN_UNITS_PER_PX = 1e-9
internal const val MAX_UNITS_PER_PX = 1.0 / 256.0

private const val FIT_PADDING = 1.2
private const val MIN_FIT_SPAN_UNITS = 1e-5 // ~ city block; avoids infinite zoom on 1 point
private const val MIN_TILE_ZOOM = 3
private const val MAX_TILE_ZOOM = 19

// Budget per view. 24 fits a portrait phone one zoom step below 1:1 (×2
// upscale worst case) while keeping per-view OSM traffic modest.
private const val MAX_TILES_PER_VIEW = 24
