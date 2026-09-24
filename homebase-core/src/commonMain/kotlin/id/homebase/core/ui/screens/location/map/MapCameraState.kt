package id.homebase.core.ui.screens.location.map

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Hoisted camera for [TiledMapView]: lets a screen observe where the map is looking and move it
 * programmatically — the share-location screen reads [centerUnit] to resolve the address under
 * its fixed center pin and calls [animateCenterTo] for the GPS re-center button.
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

    /** The bbox fit for the measured canvas (null until measured). Written by [TiledMapView]. */
    internal var fit by mutableStateOf<MapViewport?>(null)

    /** The viewport the map renders: the override when one is set, else the bbox fit. */
    internal val effective: MapViewport?
        get() = viewport ?: fit

    /** Current view center in unit space, or null until the map has measured. */
    val centerUnit: Pair<Double, Double>?
        get() = effective?.let { it.centerX to it.centerY }

    /**
     * True once a gesture (or [animateCenterTo]) has overridden the bbox fit — i.e. the view no
     * longer follows programmatic bbox updates. Lets a screen refine its initial position from a
     * late GPS fix only while the user hasn't taken over.
     */
    var isUserPositioned by mutableStateOf(false)
        internal set

    private var motionJob: Job? = null

    internal fun stopMotion() {
        motionJob?.cancel()
    }

    internal fun applyGesture(anchor: Offset, pan: Offset, zoom: Float) {
        val current = effective ?: return
        isUserPositioned = true
        viewport = current.transformed(anchor, pan, zoom)
    }

    /** Glide the view center to a unit-space point, keeping the zoom. No-op until the map has measured. */
    suspend fun animateCenterTo(unitX: Double, unitY: Double, spec: AnimationSpec<Float>) {
        val from = effective ?: return
        isUserPositioned = true
        animateTo(from.copy(centerX = unitX, centerY = unitY), spec)
    }

    internal suspend fun animateTo(target: MapViewport, spec: AnimationSpec<Float>) = motion {
        val from = effective ?: return@motion
        animate(0f, 1f, animationSpec = spec) { t, _ -> viewport = from.lerpTo(target, t) }
    }

    internal suspend fun zoomAround(anchor: Offset, factor: Float, spec: AnimationSpec<Float>) = motion {
        val from = effective ?: return@motion
        isUserPositioned = true
        animate(1f, factor, animationSpec = spec) { zoom, _ ->
            viewport = from.transformed(anchor, Offset.Zero, zoom)
        }
    }

    internal suspend fun fling(velocity: Velocity) = motion {
        var last = Offset.Zero
        AnimationState(Offset.VectorConverter, Offset.Zero, Offset(velocity.x, velocity.y))
            .animateDecay(exponentialDecay()) {
                applyGesture(Offset.Zero, value - last, 1f)
                last = value
            }
    }

    // A new motion (or a touch via stopMotion) cancels the running one; the caller still resumes, told
    // whether it ran to the end.
    private suspend fun motion(block: suspend () -> Unit): Boolean = coroutineScope {
        motionJob?.cancel()
        val job = launch { block() }.also { motionJob = it }
        job.join()
        !job.isCancelled
    }
}

@Composable
fun rememberMapCameraState(): MapCameraState = remember { MapCameraState() }
