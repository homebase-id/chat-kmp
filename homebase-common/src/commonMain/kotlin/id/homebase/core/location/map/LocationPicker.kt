package id.homebase.core.location.map

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import id.homebase.resources.MR
import id.homebase.resources.cd_location_pin
import id.homebase.resources.share_location_recenter_cd
import id.homebase.resources.share_location_resolving
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos

/**
 * A map to pick a place on: a pin fixed on its middle, the place's address above it as it's panned, and a button that
 * moves it to where this device is. [camera] gives the picked point, [MapCameraState.centerUnit].
 */
@Composable
fun LocationPicker(
    bbox: DoubleArray?,
    fetchTile: suspend (zoom: Int, x: Int, y: Int) -> ByteArray?,
    camera: MapCameraState,
    address: PinAddress.State,
    acquiringFix: Boolean,
    onRecenter: () -> Unit,
    modifier: Modifier = Modifier,
    resetViewportOn: Any? = Unit,
) {
    val motion = MaterialTheme.motionScheme
    Box(modifier) {
        TiledMapView(
            bbox = bbox,
            showMapTiles = true,
            fetchTile = fetchTile,
            resetViewportOn = resetViewportOn,
            cameraState = camera,
            modifier = Modifier.matchParentSize(),
        )
        // Up half its size, so its tip marks the middle.
        Icon(
            imageVector = Icons.Default.LocationOn,
            contentDescription = stringResource(MR.string.cd_location_pin),
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.align(Alignment.Center).size(40.dp).offset(y = (-20).dp),
        )
        val text = if (address.resolving) stringResource(MR.string.share_location_resolving) else address.address
        AnimatedVisibility(
            visible = text.isNotEmpty(),
            modifier = Modifier.align(Alignment.TopCenter),
            enter = fadeIn(motion.defaultEffectsSpec()),
            exit = fadeOut(motion.fastEffectsSpec()),
        ) {
            Surface(
                modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 72.dp),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 2.dp,
            ) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
        FilledTonalIconButton(
            onClick = onRecenter,
            modifier = Modifier.align(Alignment.TopEnd).padding(top = 12.dp, end = 12.dp),
        ) {
            Crossfade(targetState = acquiringFix, animationSpec = motion.defaultEffectsSpec()) { acquiring ->
                if (acquiring) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Icon(imageVector = Icons.Default.MyLocation, contentDescription = stringResource(MR.string.share_location_recenter_cd))
                }
            }
        }
    }
}

/**
 * The address of a [LocationPicker]'s pin as it's panned: looked up once the pin rests, within Nominatim's budget, and
 * not again for a move of under ~30 m. Without an address, its coordinates.
 */
class PinAddress(private val scope: CoroutineScope, private val reverseGeocode: suspend (lat: Double, lon: Double) -> String?) {
    data class State(val address: String = "", val resolving: Boolean = false)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var job: Job? = null
    private var lastLat: Double? = null
    private var lastLon: Double? = null

    fun moved(lat: Double, lon: Double) {
        val fromLat = lastLat
        val fromLon = lastLon
        if (fromLat != null && fromLon != null && approxDistanceMeters(fromLat, fromLon, lat, lon) < MIN_MOVE_METERS) {
            // Back where it was resolved: a lookup still on its way for a point further off would name the wrong place.
            if (job?.isActive == true) {
                job?.cancel()
                _state.update { it.copy(resolving = false) }
            }
            return
        }
        _state.update { it.copy(resolving = true) }
        job?.cancel()
        job = scope.launch {
            delay(DEBOUNCE_MS)
            val address = runCatching { reverseGeocode(lat, lon) }.getOrNull()
            lastLat = lat
            lastLon = lon
            _state.value = State(address = address ?: formatLatLon(lat, lon), resolving = false)
        }
    }

    private companion object {
        const val DEBOUNCE_MS = 700L
        const val MIN_MOVE_METERS = 30.0
        const val METERS_PER_DEGREE = 111_320.0

        // Small-distance approximation: plenty for "did the pin really move".
        fun approxDistanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val dLat = abs(lat2 - lat1) * METERS_PER_DEGREE
            val dLon = abs(lon2 - lon1) * METERS_PER_DEGREE * cos(lat1 * PI / 180.0)
            return dLat + dLon
        }

        fun formatLatLon(lat: Double, lon: Double): String {
            val latStr = ((lat * 1e5).toLong() / 1e5).toString()
            val lonStr = ((lon * 1e5).toLong() / 1e5).toString()
            return "$latStr, $lonStr"
        }
    }
}
