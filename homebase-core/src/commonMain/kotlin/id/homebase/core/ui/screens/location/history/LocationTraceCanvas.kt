package id.homebase.core.ui.screens.location.history

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import id.homebase.api.client.location.WebMercator
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.decodeToImageBitmap
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Trace colors for drawing on the map. Deliberately NOT theme colors: the OSM
 * basemap doesn't follow the app theme, and Material pastels wash out on it.
 * First color = the classic location-pin red; the rest stay saturated and
 * distinguishable for multi-device days.
 */
val mapTraceColors = listOf(
    Color(0xFFEA4335), // pin red
    Color(0xFF4285F4), // map blue
    Color(0xFF34A853), // map green
    Color(0xFF9C27B0), // purple
)

/**
 * Viewport over Web-Mercator unit space (0..1 world): a center point plus the
 * unit-width of one screen pixel. Pure data so gestures and tile math share it.
 */
private data class Viewport(val centerX: Double, val centerY: Double, val unitsPerPx: Double)

private data class TileKey(val zoom: Int, val x: Int, val y: Int)

@Composable
fun LocationTraceCanvas(
    traces: List<DeviceTrace>,
    showMapTiles: Boolean,
    fetchTile: suspend (zoom: Int, x: Int, y: Int) -> ByteArray?,
    traceColors: List<Color>,
    modifier: Modifier = Modifier,
    /** False for preview cards: no pan/zoom gestures (the card itself is clickable). */
    interactive: Boolean = true,
    /** Emphasize each trace's final position (Find device): bigger dot + halo. */
    highlightLast: Boolean = false,
    /**
     * Playback: when set, each trace is drawn only up to this instant (with an
     * interpolated leading edge + a playhead marker on the active segment). Null =
     * the static full-day view.
     */
    playbackClockMs: Long? = null,
    /** Places the user lingered; drawn as dots sized by dwell length (see [dwellRadiusDp]). */
    dwellStops: List<DwellStop> = emptyList(),
) {
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    // User pan/zoom override; null = fit the day's bbox. Reset when the day's data changes.
    var userViewport by remember(traces) { mutableStateOf<Viewport?>(null) }

    // Project once per data change: trace -> segments -> unit-space points.
    val unitTraces = remember(traces) {
        traces.map { trace ->
            trace.segments.map { segment ->
                segment.map { p -> WebMercator.latLonToUnit(p.lat, p.lon) }
            }
        }
    }
    val unitStops = remember(dwellStops) {
        dwellStops.map { WebMercator.latLonToUnit(it.lat, it.lon) }
    }
    val bbox = remember(unitTraces) {
        var minX = Double.MAX_VALUE; var minY = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE; var maxY = -Double.MAX_VALUE
        unitTraces.forEach { segs ->
            segs.forEach { seg ->
                seg.forEach { (x, y) ->
                    minX = min(minX, x); maxX = max(maxX, x)
                    minY = min(minY, y); maxY = max(maxY, y)
                }
            }
        }
        if (minX > maxX) null else doubleArrayOf(minX, minY, maxX, maxY)
    }

    val viewport = userViewport ?: fitViewport(bbox, canvasSize)

    // ── Tile layer state ──
    val tileBitmaps = remember { mutableStateMapOf<TileKey, ImageBitmap>() }
    val visibleTiles = if (showMapTiles && viewport != null && canvasSize != IntSize.Zero) {
        visibleTileKeys(viewport, canvasSize)
    } else emptyList()
    LaunchedEffect(visibleTiles, showMapTiles) {
        if (!showMapTiles) return@LaunchedEffect
        // Concurrent fetches: the provider single-flights and runs downloads on
        // its own scope, so restarts of this effect (pan/zoom) neither cancel
        // nor duplicate them. Failures are simply retried on the next restart.
        for (key in visibleTiles) {
            if (tileBitmaps.containsKey(key)) continue
            launch {
                val bytes = fetchTile(key.zoom, key.x, key.y)
                val bitmap = bytes?.let { runCatching { it.decodeToImageBitmap() }.getOrNull() }
                if (bitmap != null) tileBitmaps[key] = bitmap
            }
        }
    }

    val gestureModifier = if (!interactive) Modifier else {
        Modifier.pointerInput(traces) {
            detectTransformGestures { centroid, pan, zoom, _ ->
                val current = userViewport ?: fitViewport(bbox, canvasSize) ?: return@detectTransformGestures
                val newUnitsPerPx = (current.unitsPerPx / zoom)
                    .coerceIn(MIN_UNITS_PER_PX, MAX_UNITS_PER_PX)
                // Keep the gesture centroid anchored while zooming, then pan.
                val cx = centroid.x - size.width / 2f
                val cy = centroid.y - size.height / 2f
                val anchoredX = current.centerX + cx * (current.unitsPerPx - newUnitsPerPx)
                val anchoredY = current.centerY + cy * (current.unitsPerPx - newUnitsPerPx)
                userViewport = Viewport(
                    centerX = anchoredX - pan.x * newUnitsPerPx,
                    centerY = anchoredY - pan.y * newUnitsPerPx,
                    unitsPerPx = newUnitsPerPx,
                )
            }
        }
    }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { canvasSize = it }
            .then(gestureModifier),
    ) {
        val vp = viewport ?: return@Canvas
        fun toPx(unit: Pair<Double, Double>): Offset = Offset(
            x = ((unit.first - vp.centerX) / vp.unitsPerPx + size.width / 2.0).toFloat(),
            y = ((unit.second - vp.centerY) / vp.unitsPerPx + size.height / 2.0).toFloat(),
        )

        // ── Basemap tiles (under the traces) ──
        for (key in visibleTiles) {
            val bitmap = tileBitmaps[key] ?: continue
            val b = WebMercator.tileToUnitBounds(key.x, key.y, key.zoom)
            val topLeft = toPx(b[0] to b[1])
            val bottomRight = toPx(b[2] to b[3])
            drawImage(
                image = bitmap,
                dstOffset = IntOffset(topLeft.x.roundToInt(), topLeft.y.roundToInt()),
                dstSize = IntSize(
                    width = (bottomRight.x - topLeft.x).roundToInt().coerceAtLeast(1),
                    height = (bottomRight.y - topLeft.y).roundToInt().coerceAtLeast(1),
                ),
            )
        }

        // ── Traces ──
        val strokeWidth = 3.dp.toPx()
        val dotRadius = 5.dp.toPx()
        val clock = playbackClockMs
        // Playhead positions to draw on top after the dwell dots (one per active trace).
        val playheads = mutableListOf<Pair<Offset, Color>>()
        unitTraces.forEachIndexed { traceIndex, segments ->
            val color = traceColors[traceIndex % traceColors.size]
            val tsegs = traces[traceIndex].segments
            var tracePlayhead: Pair<Double, Double>? = null
            segments.forEachIndexed { segIndex, unitSeg ->
                if (unitSeg.isEmpty()) return@forEachIndexed
                val times = tsegs[segIndex]
                // Clip the segment to `clock`: keep points already reached, then
                // interpolate one leading edge to the exact instant (= the playhead).
                val drawPts: List<Pair<Double, Double>> = if (clock == null) {
                    unitSeg
                } else {
                    if (clock < times.first().t) return@forEachIndexed // not started yet
                    val cutoff = times.count { it.t <= clock }
                    if (cutoff >= unitSeg.size) {
                        unitSeg
                    } else {
                        val p0 = unitSeg[cutoff - 1]
                        val p1 = unitSeg[cutoff]
                        val t0 = times[cutoff - 1].t
                        val t1 = times[cutoff].t
                        val f = if (t1 > t0) (clock - t0).toDouble() / (t1 - t0) else 0.0
                        val head = p0.first + (p1.first - p0.first) * f to
                            p0.second + (p1.second - p0.second) * f
                        tracePlayhead = head
                        unitSeg.subList(0, cutoff) + head
                    }
                }
                if (drawPts.size == 1) {
                    drawCircle(color, radius = strokeWidth, center = toPx(drawPts.first()))
                    return@forEachIndexed
                }
                val path = Path()
                val first = toPx(drawPts.first())
                path.moveTo(first.x, first.y)
                for (i in 1 until drawPts.size) {
                    val p = toPx(drawPts[i])
                    path.lineTo(p.x, p.y)
                }
                if (showMapTiles) {
                    // Cartography casing: a wider white underlay keeps the
                    // route readable over any tile content.
                    drawPath(
                        path = path,
                        color = Color.White.copy(alpha = 0.9f),
                        style = Stroke(width = strokeWidth * 2.0f, cap = StrokeCap.Round, join = StrokeJoin.Round),
                    )
                }
                drawPath(
                    path = path,
                    color = color,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
            }
            // Start dot once the trace has begun.
            val started = clock == null || (tsegs.firstOrNull()?.firstOrNull()?.let { clock >= it.t } ?: false)
            if (started) segments.firstOrNull()?.firstOrNull()?.let {
                val start = toPx(it)
                if (showMapTiles) drawCircle(Color.White, dotRadius * 1.4f, start)
                drawCircle(color, dotRadius, start)
            }
            // End marker only once the whole trace is in the past (or static view).
            val lastT = tsegs.lastOrNull()?.lastOrNull()?.t
            val finished = clock == null || (lastT != null && clock >= lastT)
            if (finished) segments.lastOrNull()?.lastOrNull()?.let {
                val end = toPx(it)
                if (highlightLast) {
                    // Find-device emphasis: soft halo + solid dot + ring.
                    drawCircle(color.copy(alpha = 0.25f), dotRadius * 3.2f, end)
                    drawCircle(color, dotRadius * 1.6f, end)
                    drawCircle(color, dotRadius * 2.4f, end, style = Stroke(width = strokeWidth))
                } else {
                    drawCircle(color, dotRadius, end, style = Stroke(width = strokeWidth))
                }
            }
            tracePlayhead?.let { playheads.add(toPx(it) to color) }
        }

        // ── Dwell dots (always drawn when present; grow as the clock sweeps the stay) ──
        unitStops.forEachIndexed { i, unit ->
            val stop = dwellStops[i]
            val effDur = if (clock == null) {
                stop.durationMs
            } else {
                if (clock < stop.startMs) return@forEachIndexed // not reached yet
                minOf(clock, stop.endMs) - stop.startMs
            }
            val r = dwellRadiusDp(effDur).dp.toPx()
            val c = traceColors[stop.deviceIndex % traceColors.size]
            val center = toPx(unit)
            drawCircle(c.copy(alpha = 0.20f), r, center)
            drawCircle(c, r, center, style = Stroke(width = 2.dp.toPx()))
        }

        // ── Playheads (on top) ──
        playheads.forEach { (center, color) ->
            drawCircle(Color.White, dotRadius * 1.5f, center)
            drawCircle(color, dotRadius * 1.1f, center)
        }
    }
}

private fun fitViewport(bbox: DoubleArray?, canvasSize: IntSize): Viewport? {
    if (bbox == null || canvasSize.width == 0 || canvasSize.height == 0) return null
    val minX = bbox[0]; val minY = bbox[1]; val maxX = bbox[2]; val maxY = bbox[3]
    val spanX = max(maxX - minX, MIN_FIT_SPAN_UNITS)
    val spanY = max(maxY - minY, MIN_FIT_SPAN_UNITS)
    val unitsPerPx = max(
        spanX * FIT_PADDING / canvasSize.width,
        spanY * FIT_PADDING / canvasSize.height,
    ).coerceIn(MIN_UNITS_PER_PX, MAX_UNITS_PER_PX)
    return Viewport(
        centerX = (minX + maxX) / 2,
        centerY = (minY + maxY) / 2,
        unitsPerPx = unitsPerPx,
    )
}

private fun visibleTileKeys(vp: Viewport, canvasSize: IntSize): List<TileKey> {
    // Start at the zoom where one tile (256px-native) renders ~1:1, then step
    // DOWN until the full visible grid fits the tile budget — a portrait phone
    // at 1:1 needs ~30-40 tiles, so coverage beats crispness: coarser tiles are
    // upscaled but the whole view gets a basemap (truncating the grid instead
    // left the bottom of the screen bare).
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
                    add(TileKey(zoom, x, y))
                    if (size >= MAX_TILES_PER_VIEW) return@buildList
                }
            }
        }
    }
    return emptyList()
}

private const val FIT_PADDING = 1.2
private const val MIN_FIT_SPAN_UNITS = 1e-5 // ~ city block; avoids infinite zoom on 1 point
private const val MIN_UNITS_PER_PX = 1e-9
private const val MAX_UNITS_PER_PX = 1.0 / 256.0
private const val MIN_TILE_ZOOM = 3
private const val MAX_TILE_ZOOM = 19

// Budget per view. 24 fits a portrait phone one zoom step below 1:1 (×2
// upscale worst case) while keeping per-view OSM traffic modest.
private const val MAX_TILES_PER_VIEW = 24
