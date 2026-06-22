package id.homebase.core.ui.screens.location.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import id.homebase.api.client.location.WebMercator
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.decodeToImageBitmap
import kotlin.math.roundToInt

/**
 * Generic zoomable, pannable map: an OSM tile basemap plus a caller-supplied overlay. The single
 * shared map core — History traces, the dashboard preview card, Find Device, and the live-location
 * map all render through this, so they share one viewport/zoom/tile heuristic ([MapProjection]).
 *
 * Two overlay slots, both reading the SAME live viewport so they stay pixel-anchored during zoom/pan:
 *  - [onDrawOverlay] — Canvas drawing on top of the tiles (History uses it for trace polylines,
 *    playheads, dwell dots). Receives `project(unitX, unitY) -> Offset`.
 *  - [markerContent] — composables on top of the canvas (the live map uses it for avatar dots).
 *    Receives the same `project` plus `ready` (false until the viewport/canvas are measured). Markers
 *    should position themselves with `Modifier.offset { project(...) }` so a gesture frame re-runs
 *    only placement math — no recomposition, no image reload.
 *
 * Coordinates are Web-Mercator unit space; convert lat/lon with [WebMercator.latLonToUnit].
 *
 * @param bbox unit-space `[minX,minY,maxX,maxY]` to fit on first layout (and re-fit until the first
 *   user gesture); null = nothing to fit yet.
 * @param resetViewportOn when this key changes the user's pan/zoom is dropped and the view re-fits to
 *   [bbox]. History passes its `traces` (new day re-fits); the live map passes `Unit` (preserve
 *   pan/zoom across store updates).
 */
@Composable
fun TiledMapView(
    bbox: DoubleArray?,
    showMapTiles: Boolean,
    fetchTile: suspend (zoom: Int, x: Int, y: Int) -> ByteArray?,
    modifier: Modifier = Modifier,
    interactive: Boolean = true,
    resetViewportOn: Any? = Unit,
    onDrawOverlay: (DrawScope.(project: (Double, Double) -> Offset) -> Unit)? = null,
    markerContent: (@Composable (project: (Double, Double) -> Offset, ready: Boolean) -> Unit)? = null,
) {
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    // User pan/zoom override; null = fit the bbox. Reset when resetViewportOn changes.
    var userViewport by remember(resetViewportOn) { mutableStateOf<MapViewport?>(null) }

    val viewport = userViewport ?: fitViewport(bbox, canvasSize)

    // ── Tile layer state ──
    val tileBitmaps = remember { mutableStateMapOf<MapTileKey, ImageBitmap>() }
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
        Modifier.pointerInput(resetViewportOn) {
            detectTransformGestures { centroid, pan, zoom, _ ->
                val current = userViewport ?: fitViewport(bbox, canvasSize) ?: return@detectTransformGestures
                val newUnitsPerPx = (current.unitsPerPx / zoom)
                    .coerceIn(MIN_UNITS_PER_PX, MAX_UNITS_PER_PX)
                // Keep the gesture centroid anchored while zooming, then pan.
                val cx = centroid.x - size.width / 2f
                val cy = centroid.y - size.height / 2f
                val anchoredX = current.centerX + cx * (current.unitsPerPx - newUnitsPerPx)
                val anchoredY = current.centerY + cy * (current.unitsPerPx - newUnitsPerPx)
                userViewport = MapViewport(
                    centerX = anchoredX - pan.x * newUnitsPerPx,
                    centerY = anchoredY - pan.y * newUnitsPerPx,
                    unitsPerPx = newUnitsPerPx,
                )
            }
        }
    }

    Box(modifier = modifier.fillMaxSize().onSizeChanged { canvasSize = it }.then(gestureModifier)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val vp = viewport ?: return@Canvas
            fun project(ux: Double, uy: Double): Offset = vp.toPx(ux, uy, size.width, size.height)

            // ── Basemap tiles (under the overlay) ──
            for (key in visibleTiles) {
                val bitmap = tileBitmaps[key] ?: continue
                val b = WebMercator.tileToUnitBounds(key.x, key.y, key.zoom)
                val topLeft = project(b[0], b[1])
                val bottomRight = project(b[2], b[3])
                drawImage(
                    image = bitmap,
                    dstOffset = IntOffset(topLeft.x.roundToInt(), topLeft.y.roundToInt()),
                    dstSize = IntSize(
                        width = (bottomRight.x - topLeft.x).roundToInt().coerceAtLeast(1),
                        height = (bottomRight.y - topLeft.y).roundToInt().coerceAtLeast(1),
                    ),
                )
            }

            // ── Caller overlay (traces, playheads, dwell dots, …) ──
            onDrawOverlay?.invoke(this) { ux, uy -> project(ux, uy) }
        }

        // ── Composable marker overlay (avatar dots, …) ──
        if (markerContent != null) {
            val vp = viewport
            val ready = vp != null && canvasSize != IntSize.Zero
            val w = canvasSize.width.toFloat()
            val h = canvasSize.height.toFloat()
            markerContent({ ux, uy ->
                vp?.toPx(ux, uy, w, h) ?: Offset.Zero
            }, ready)
        }
    }
}
