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
import org.jetbrains.compose.resources.decodeToImageBitmap
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

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
    val failedTiles = remember { mutableStateMapOf<TileKey, Boolean>() }
    val visibleTiles = if (showMapTiles && viewport != null && canvasSize != IntSize.Zero) {
        visibleTileKeys(viewport, canvasSize)
    } else emptyList()
    LaunchedEffect(visibleTiles, showMapTiles) {
        if (!showMapTiles) return@LaunchedEffect
        for (key in visibleTiles) {
            if (tileBitmaps.containsKey(key) || failedTiles.containsKey(key)) continue
            val bytes = fetchTile(key.zoom, key.x, key.y)
            val bitmap = bytes?.let { runCatching { it.decodeToImageBitmap() }.getOrNull() }
            if (bitmap != null) tileBitmaps[key] = bitmap else failedTiles[key] = true
        }
    }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { canvasSize = it }
            .pointerInput(traces) {
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
            },
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
        unitTraces.forEachIndexed { traceIndex, segments ->
            val color = traceColors[traceIndex % traceColors.size]
            for (segment in segments) {
                if (segment.isEmpty()) continue
                if (segment.size == 1) {
                    drawCircle(color, radius = strokeWidth, center = toPx(segment.first()))
                    continue
                }
                val path = Path()
                val first = toPx(segment.first())
                path.moveTo(first.x, first.y)
                for (i in 1 until segment.size) {
                    val p = toPx(segment[i])
                    path.lineTo(p.x, p.y)
                }
                drawPath(
                    path = path,
                    color = color,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
            }
            // Start dot (filled) and end marker (ring) for the whole trace.
            segments.firstOrNull()?.firstOrNull()?.let { drawCircle(color, dotRadius, toPx(it)) }
            segments.lastOrNull()?.lastOrNull()?.let {
                drawCircle(color, dotRadius, toPx(it), style = Stroke(width = strokeWidth))
            }
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
    // Pick the zoom where one tile (256px-native) renders at roughly 1:1.
    val zoom = (-ln(vp.unitsPerPx * WebMercator.TILE_SIZE_PX) / ln(2.0))
        .roundToInt().coerceIn(MIN_TILE_ZOOM, MAX_TILE_ZOOM)
    val n = 1 shl zoom
    val halfW = canvasSize.width / 2.0 * vp.unitsPerPx
    val halfH = canvasSize.height / 2.0 * vp.unitsPerPx
    val x0 = floor((vp.centerX - halfW) * n).toInt().coerceIn(0, n - 1)
    val x1 = ceil((vp.centerX + halfW) * n).toInt().coerceIn(0, n - 1)
    val y0 = floor((vp.centerY - halfH) * n).toInt().coerceIn(0, n - 1)
    val y1 = ceil((vp.centerY + halfH) * n).toInt().coerceIn(0, n - 1)
    val keys = mutableListOf<TileKey>()
    outer@ for (y in y0..y1) {
        for (x in x0..x1) {
            keys += TileKey(zoom, x, y)
            if (keys.size >= MAX_TILES_PER_VIEW) break@outer
        }
    }
    return keys
}

private const val FIT_PADDING = 1.2
private const val MIN_FIT_SPAN_UNITS = 1e-5 // ~ city block; avoids infinite zoom on 1 point
private const val MIN_UNITS_PER_PX = 1e-9
private const val MAX_UNITS_PER_PX = 1.0 / 256.0
private const val MIN_TILE_ZOOM = 3
private const val MAX_TILE_ZOOM = 19
private const val MAX_TILES_PER_VIEW = 16
