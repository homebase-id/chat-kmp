package id.homebase.photobase.info

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import id.homebase.api.client.location.WebMercator
import id.homebase.api.media.MediaLocation
import id.homebase.core.location.map.TiledMapView
import id.homebase.core.location.map.rememberMapCameraState
import id.homebase.resources.MR
import id.homebase.resources.cancel
import id.homebase.resources.cd_location_pin
import id.homebase.resources.ok
import id.homebase.resources.photobase_info_edit_location
import id.homebase.resources.photobase_info_edit_location_hint
import id.homebase.resources.photobase_info_edit_time
import id.homebase.resources.photobase_info_latitude
import id.homebase.resources.photobase_info_longitude
import id.homebase.resources.photobase_info_remove_location
import id.homebase.resources.save
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.UtcOffset
import kotlinx.datetime.asTimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Instant

/** The day, then the time, on the clock at [offset]: the zone it shows the photo in. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EditTimeDialogs(at: Instant, offset: UtcOffset, onSave: (Instant, UtcOffset) -> Unit, onDismiss: () -> Unit) {
    val local = remember(at, offset) { at.toLocalDateTime(offset.asTimeZone()) }
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
                    onSave(chosen.toInstant(offset), offset)
                }) { Text(stringResource(MR.string.save)) }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(MR.string.cancel)) } },
        )
    }
}

/**
 * The place under the pin in the map's middle, or, with Maps at None, typed in: no tiles are fetched then. Null from
 * [onSave] removes it.
 */
@Composable
internal fun EditPlaceDialog(at: MediaLocation?, source: PhotoInfoSource, onSave: (MediaLocation?) -> Unit, onDismiss: () -> Unit) {
    val camera = rememberMapCameraState()
    var latitude by remember { mutableStateOf(at?.latitude?.toString().orEmpty()) }
    var longitude by remember { mutableStateOf(at?.longitude?.toString().orEmpty()) }
    val typed = typedLocation(latitude, longitude)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(MR.string.photobase_info_edit_location)) },
        text = {
            if (source.mapsOnline) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(MR.string.photobase_info_edit_location_hint), style = MaterialTheme.typography.bodyMedium)
                    Box(Modifier.fillMaxWidth().height(280.dp).clip(RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                        val box = remember(at) { at?.let { WebMercator.latLonToUnit(it.latitude, it.longitude) }?.let { (x, y) -> mapBox(x, y) } ?: WORLD }
                        TiledMapView(bbox = box, showMapTiles = true, fetchTile = source::tile, cameraState = camera, modifier = Modifier.fillMaxWidth().height(280.dp))
                        // Its point on the middle.
                        Icon(
                            Icons.Filled.LocationOn,
                            contentDescription = stringResource(MR.string.cd_location_pin),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(PIN).offset(y = -PIN / 2),
                        )
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        latitude, { latitude = it },
                        label = { Text(stringResource(MR.string.photobase_info_latitude)) },
                        singleLine = true,
                        isError = latitude.isNotBlank() && typed == null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                    OutlinedTextField(
                        longitude, { longitude = it },
                        label = { Text(stringResource(MR.string.photobase_info_longitude)) },
                        singleLine = true,
                        isError = longitude.isNotBlank() && typed == null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                }
            }
        },
        confirmButton = {
            val chosen = if (source.mapsOnline) camera.centerUnit?.let { (x, y) -> WebMercator.unitToLatLon(x, y) }?.let { (lat, lon) -> MediaLocation(lat, lon) } else typed
            TextButton(onClick = { chosen?.let(onSave) }, enabled = chosen != null) { Text(stringResource(MR.string.save)) }
        },
        dismissButton = {
            Row {
                if (at != null) TextButton(onClick = { onSave(null) }) { Text(stringResource(MR.string.photobase_info_remove_location)) }
                TextButton(onClick = onDismiss) { Text(stringResource(MR.string.cancel)) }
            }
        },
    )
}

internal fun typedLocation(latitude: String, longitude: String): MediaLocation? {
    val lat = latitude.trim().toDoubleOrNull()?.takeIf { it in -90.0..90.0 } ?: return null
    val lon = longitude.trim().toDoubleOrNull()?.takeIf { it in -180.0..180.0 } ?: return null
    return MediaLocation(lat, lon)
}

private val WORLD = doubleArrayOf(0.0, 0.0, 1.0, 1.0)
private val PIN = 32.dp
