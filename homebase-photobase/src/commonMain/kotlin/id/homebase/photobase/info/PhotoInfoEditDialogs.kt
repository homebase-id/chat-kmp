package id.homebase.photobase.info

import androidx.compose.foundation.layout.Arrangement
import org.jetbrains.compose.resources.StringResource
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.filterNotNull
import id.homebase.core.location.tracking.GpsFixResult
import id.homebase.core.location.rememberCurrentGps
import id.homebase.core.location.map.PinAddress
import id.homebase.core.location.map.MapCameraState
import id.homebase.core.location.map.LocationPicker
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Dialog
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.filled.Close
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import id.homebase.api.client.location.WebMercator
import id.homebase.api.media.MediaLocation
import id.homebase.core.location.map.rememberMapCameraState
import id.homebase.resources.MR
import id.homebase.resources.cancel
import id.homebase.resources.ok
import id.homebase.resources.photobase_info_edit_location
import id.homebase.resources.photobase_info_edit_time
import id.homebase.resources.photobase_info_latitude
import id.homebase.resources.photobase_info_longitude
import id.homebase.resources.photobase_info_remove_location
import id.homebase.resources.save
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.stringResource
import id.homebase.common.util.formatDecimal
import kotlin.math.abs
import kotlin.math.floor
import kotlin.time.Instant

/** The day, then the time, on the clock of [zone]: the one it shows the photo in. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EditTimeDialogs(at: Instant, zone: TimeZone, onSave: (Instant) -> Unit, onDismiss: () -> Unit) {
    val local = remember(at, zone) { at.toLocalDateTime(zone) }
    var day by remember { mutableStateOf<LocalDate?>(null) }
    val picked = day
    if (picked == null) {
        // The picker counts days in UTC.
        val state = rememberDatePickerState(initialSelectedDateMillis = local.date.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds())
        DatePickerDialog(
            onDismissRequest = onDismiss,
            confirmButton = {
                TextButton(
                    onClick = { state.selectedDateMillis?.let { day = Instant.fromEpochMilliseconds(it).toLocalDateTime(TimeZone.UTC).date } },
                    enabled = state.selectedDateMillis != null,
                ) { Text(stringResource(MR.string.ok)) }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(MR.string.cancel)) } },
        ) {
            DatePicker(state = state, title = { Text(stringResource(MR.string.photobase_info_edit_time), Modifier.offset(x = 24.dp, y = 16.dp)) })
        }
    } else {
        val state = rememberTimePickerState(initialHour = local.hour, initialMinute = local.minute)
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(MR.string.photobase_info_edit_time)) },
            text = { TimePicker(state = state) },
            confirmButton = {
                TextButton(onClick = {
                    val chosen = LocalDateTime(picked, LocalTime(state.hour, state.minute, local.second))
                    // Nothing changed is nothing to save: it would mark the time as set by hand.
                    if (chosen.date == local.date && chosen.hour == local.hour && chosen.minute == local.minute) {
                        onDismiss()
                    } else {
                        // In the zone, not at today's offset: a day across a change of clock gets that day's.
                        onSave(chosen.toInstant(zone))
                    }
                }) { Text(stringResource(MR.string.save)) }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(MR.string.cancel)) } },
        )
    }
}


/**
 * The place under the pin in the map's middle, as share-location picks it; with Maps at None, typed in, and no tiles
 * fetched. Saves only a change. Null from [onSave] removes it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EditPlaceDialog(at: MediaLocation?, source: PhotoInfoSource, onSave: (MediaLocation?) -> Unit, onDismiss: () -> Unit) {
    val camera = rememberMapCameraState()
    // formatDecimal, not toString: near 0 that writes "1.0E-5".
    var latitude by remember { mutableStateOf(at?.latitude?.let { formatDecimal(it, 6) }.orEmpty()) }
    var longitude by remember { mutableStateOf(at?.longitude?.let { formatDecimal(it, 6) }.orEmpty()) }
    val typed = typedLocation(latitude, longitude)?.takeIf { it.movedFrom(at) }
    // Derived, so a pan frame doesn't recompose the dialog: the point itself is read on Save.
    // Moved off it, not just zoomed: a save marks the place as set by hand and drops the camera's GPS details.
    val canSave by remember(source.mapsOnline) { derivedStateOf { source.mapsOnline && camera.isUserPositioned && camera.pickedPoint()?.let { it.movedFrom(at) } == true } }
    val chosen = { if (source.mapsOnline) camera.pickedPoint()?.takeIf { camera.isUserPositioned } else typed }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(MR.string.photobase_info_edit_location)) },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = stringResource(MR.string.cancel)) }
                    },
                    actions = {
                        TextButton(onClick = { chosen()?.let(onSave) }, enabled = if (source.mapsOnline) canSave else typed != null) { Text(stringResource(MR.string.save)) }
                    },
                )
            },
            bottomBar = {
                if (at != null) {
                    TextButton(onClick = { onSave(null) }, modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(8.dp)) {
                        Text(stringResource(MR.string.photobase_info_remove_location))
                    }
                }
            },
        ) { padding ->
            if (source.mapsOnline) {
                PlacePicker(at, source, camera, Modifier.padding(padding).fillMaxSize())
            } else {
                Column(Modifier.padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CoordinateField(latitude, { latitude = it }, MR.string.photobase_info_latitude, isError = latitude.isNotBlank() && latitude.coordinate()?.takeIf { it in -90.0..90.0 } == null)
                    CoordinateField(longitude, { longitude = it }, MR.string.photobase_info_longitude, isError = longitude.isNotBlank() && longitude.coordinate()?.takeIf { it in -180.0..180.0 } == null)
                }
            }
        }
    }
}

@Composable
private fun PlacePicker(at: MediaLocation?, source: PhotoInfoSource, camera: MapCameraState, modifier: Modifier) {
    val scope = rememberCoroutineScope()
    val pinAddress = remember { PinAddress(scope) { lat, lon -> source.address(lat, lon) } }
    val address by pinAddress.state.collectAsStateWithLifecycle()
    // The world, with no place yet: its middle means nothing until the map is moved.
    val box = remember(at) { at?.let { WebMercator.latLonToUnit(it.latitude, it.longitude) }?.let { (x, y) -> mapBox(x, y) } ?: WORLD }
    LaunchedEffect(camera) {
        snapshotFlow { camera.centerUnit }.filterNotNull().collect {
            if (at != null || camera.isUserPositioned) camera.pickedPoint()?.let { pinAddress.moved(it.latitude, it.longitude) }
        }
    }
    var acquiring by remember { mutableStateOf(false) }
    val recenterSpec = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    val gps = rememberCurrentGps { result ->
        acquiring = false
        if (result is GpsFixResult.Success) {
            val (x, y) = WebMercator.latLonToUnit(result.point.lat, result.point.lon)
            scope.launch { camera.animateCenterTo(x, y, recenterSpec) }
        }
    }
    LocationPicker(
        bbox = box,
        fetchTile = source::tile,
        camera = camera,
        address = address,
        acquiringFix = acquiring,
        onRecenter = {
            acquiring = true
            gps.launch()
        },
        modifier = modifier,
    )
}

// Text, not a number pad: iOS's decimal pad has no minus, so no place west or south could be typed.
@Composable
private fun CoordinateField(value: String, onValueChange: (String) -> Unit, label: StringResource, isError: Boolean) {
    OutlinedTextField(
        value, onValueChange,
        label = { Text(stringResource(label)) },
        singleLine = true,
        isError = isError,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** A comma reads as the decimal point, as a keyboard in many languages writes it. */
internal fun typedLocation(latitude: String, longitude: String): MediaLocation? =
    MediaLocation.of(latitude.coordinate(), longitude.coordinate())

private fun String.coordinate(): Double? = trim().replace(',', '.').toDoubleOrNull()

private val WORLD = doubleArrayOf(0.0, 0.0, 1.0, 1.0)

// More than ~10 m from [from], or anywhere when there was none.
private fun MediaLocation.movedFrom(from: MediaLocation?): Boolean =
    from == null || abs(latitude - from.latitude) > 0.0001 || abs(longitude - from.longitude) > 0.0001

// Panned round the world, the map's x runs past 0..1: back into -180..180, for the lookup and the save alike.
private fun MapCameraState.pickedPoint(): MediaLocation? =
    centerUnit?.let { (x, y) -> WebMercator.unitToLatLon(x - floor(x), y) }?.let { (lat, lon) -> MediaLocation.of(lat, lon) }
