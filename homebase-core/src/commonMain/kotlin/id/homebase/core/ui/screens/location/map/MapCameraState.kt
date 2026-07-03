package id.homebase.core.ui.screens.location.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * Hoisted camera for [TiledMapView]: lets a screen observe where the map is looking and move it
 * programmatically — the share-location screen reads [centerUnit] to resolve the address under
 * its fixed center pin and calls [centerOn] for the GPS re-center button.
 *
 * Create with [rememberMapCameraState] and pass to [TiledMapView]'s `cameraState`. Callers that
 * don't need camera access pass nothing — the map keeps a private instance and behaves exactly
 * as before.
 *
 * Coordinates are Web-Mercator unit space (`WebMercator.latLonToUnit`/`unitToLatLon`).
 */
@Stable
class MapCameraState {

    /** User/programmatic viewport override; null = the map fits its `bbox`. */
    internal var viewport by mutableStateOf<MapViewport?>(null)

    /**
     * The viewport the map actually rendered last composition — the override when one is set,
     * else the bbox fit (null until the canvas is measured). Written by [TiledMapView].
     */
    internal var effective by mutableStateOf<MapViewport?>(null)

    /** Current view center in unit space, or null until the map has measured. */
    val centerUnit: Pair<Double, Double>?
        get() = effective?.let { it.centerX to it.centerY }

    /**
     * True once a gesture (or [centerOn]) has overridden the bbox fit — i.e. the view no longer
     * follows programmatic bbox updates. Lets a screen refine its initial position from a late
     * GPS fix only while the user hasn't taken over.
     */
    val isUserPositioned: Boolean
        get() = viewport != null

    /**
     * Move the view center to a unit-space point, keeping the current zoom. No-op until the map
     * has rendered once (there is no zoom to keep yet — the initial bbox fit covers that frame).
     */
    fun centerOn(unitX: Double, unitY: Double) {
        val base = viewport ?: effective ?: return
        viewport = base.copy(centerX = unitX, centerY = unitY)
    }
}

@Composable
fun rememberMapCameraState(): MapCameraState = remember { MapCameraState() }
