package id.homebase.core.ui.screens.location.livelocation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.geometry.Offset
import id.homebase.api.client.location.WebMercator
import id.homebase.core.ui.screens.location.map.TiledMapView
import kotlin.math.cos
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The live-location map: a zoomable OSM basemap ([TiledMapView]) with one avatar dot per [LiveMarker].
 * Markers are composables positioned with `Modifier.offset` reading the live viewport projection, so a
 * pan/zoom frame re-runs only placement math — no recomposition, no avatar reload. `key(marker.key)`
 * keeps each avatar instance stable as positions update.
 */
@Composable
fun LiveLocationMap(
    markers: List<LiveMarker>,
    showMapTiles: Boolean,
    fetchTile: suspend (zoom: Int, x: Int, y: Int) -> ByteArray?,
    modifier: Modifier = Modifier,
) {
    // Precompute unit-space coords once per emission (cheap; N is small).
    val unitByKey = remember(markers) {
        markers.associate { it.key to WebMercator.latLonToUnit(it.lat, it.lon) }
    }
    val bbox = remember(unitByKey) {
        if (unitByKey.isEmpty()) {
            null
        } else {
            var minX = Double.MAX_VALUE; var minY = Double.MAX_VALUE
            var maxX = -Double.MAX_VALUE; var maxY = -Double.MAX_VALUE
            unitByKey.values.forEach { (x, y) ->
                if (x < minX) minX = x; if (x > maxX) maxX = x
                if (y < minY) minY = y; if (y > maxY) maxY = y
            }
            doubleArrayOf(minX, minY, maxX, maxY)
        }
    }

    TiledMapView(
        bbox = bbox,
        showMapTiles = showMapTiles,
        fetchTile = fetchTile,
        modifier = modifier,
        interactive = true,
        resetViewportOn = Unit, // preserve pan/zoom across store updates
        markerContent = { project, ready ->
            if (ready) {
                markers.forEach { marker ->
                    val unit = unitByKey[marker.key] ?: return@forEach
                    val cd = liveMarkerContentDescription(marker)
                    key(marker.key) {
                        Box(
                            modifier = Modifier
                                .wrapContentSize()
                                .offset {
                                    val o = project(unit.first, unit.second)
                                    val half = (LiveMarkerSize / 2).toPx()
                                    val fan = fanOffset(marker.clusterIndex, marker.clusterSize, half)
                                    IntOffset(
                                        (o.x - half + fan.x).roundToInt(),
                                        (o.y - half + fan.y).roundToInt(),
                                    )
                                }
                                .clearAndSetSemantics { contentDescription = cd },
                        ) {
                            LiveLocationMarker(marker)
                        }
                    }
                }
            }
        },
    )
}

/**
 * Spread for co-located markers: places slot [index] of a group of [size] evenly around a circle of
 * radius ~[markerHalfPx], so overlapping dots fan out and stay individually visible. No spread for a
 * lone marker.
 */
private fun fanOffset(index: Int, size: Int, markerHalfPx: Float): Offset {
    if (size <= 1) return Offset.Zero
    val angle = 2.0 * PI * index / size
    val radius = markerHalfPx * 1.1f
    return Offset(radius * cos(angle).toFloat(), radius * sin(angle).toFloat())
}
