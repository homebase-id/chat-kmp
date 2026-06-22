package id.homebase.core.ui.screens.location.history

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import id.homebase.api.client.location.WebMercator
import id.homebase.core.ui.screens.location.map.TiledMapView
import kotlin.math.max
import kotlin.math.min

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
 * History/Find-Device map: GPS traces (and optional playback + dwell dots) drawn over an OSM basemap.
 * A thin wrapper over the shared [TiledMapView] — this composable owns only the History-specific
 * overlay (trace polylines, playhead, dwell dots); all viewport/zoom/tile behaviour lives in
 * [TiledMapView]/[id.homebase.core.ui.screens.location.map].
 */
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

    TiledMapView(
        bbox = bbox,
        showMapTiles = showMapTiles,
        fetchTile = fetchTile,
        modifier = modifier,
        interactive = interactive,
        resetViewportOn = traces,
        onDrawOverlay = { project ->
            drawTraceOverlay(
                project = project,
                traces = traces,
                unitTraces = unitTraces,
                unitStops = unitStops,
                dwellStops = dwellStops,
                traceColors = traceColors,
                showMapTiles = showMapTiles,
                highlightLast = highlightLast,
                playbackClockMs = playbackClockMs,
            )
        },
    )
}

/**
 * The History-specific overlay: trace polylines (with optional playback clipping + playhead), start/
 * end markers, and dwell dots. Pulled out of [LocationTraceCanvas] as a [DrawScope] extension so the
 * `onDrawOverlay` slot stays a thin call.
 */
private fun DrawScope.drawTraceOverlay(
    project: (Double, Double) -> Offset,
    traces: List<DeviceTrace>,
    unitTraces: List<List<List<Pair<Double, Double>>>>,
    unitStops: List<Pair<Double, Double>>,
    dwellStops: List<DwellStop>,
    traceColors: List<Color>,
    showMapTiles: Boolean,
    highlightLast: Boolean,
    playbackClockMs: Long?,
) {
    fun toPx(unit: Pair<Double, Double>): Offset = project(unit.first, unit.second)

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
