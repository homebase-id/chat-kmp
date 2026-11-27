package id.homebase.photobase.info

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PhotoAlbum
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import id.homebase.api.client.location.WebMercator
import id.homebase.api.image.ImageMetadata
import id.homebase.api.media.MediaLocation
import id.homebase.chat.event.offsetLabel
import id.homebase.common.util.formatBytes
import id.homebase.common.util.formatDecimal
import id.homebase.core.location.map.TiledMapView
import id.homebase.core.util.formatMediumDate
import id.homebase.core.util.formatTime
import id.homebase.core.widget.AdaptiveSheet
import id.homebase.core.widget.WithTooltip
import id.homebase.photobase.gallery.PhotoGalleryItem
import id.homebase.resources.MR
import id.homebase.resources.cd_location_pin
import id.homebase.resources.photobase_info_albums
import id.homebase.resources.photobase_info_aperture
import id.homebase.resources.photobase_info_backed_up
import id.homebase.resources.photobase_info_backed_up_body
import id.homebase.resources.photobase_info_camera_failed
import id.homebase.resources.photobase_info_camera_loading
import id.homebase.resources.photobase_info_date_time
import id.homebase.resources.photobase_info_dimensions
import id.homebase.resources.photobase_info_focal_length
import id.homebase.resources.photobase_info_iso
import id.homebase.resources.photobase_info_joined
import id.homebase.resources.photobase_info_list
import id.homebase.resources.photobase_info_location
import id.homebase.resources.photobase_info_megapixels
import id.homebase.resources.photobase_info_open
import id.homebase.resources.photobase_info_open_map
import id.homebase.resources.photobase_info_place_failed
import id.homebase.resources.photobase_info_shutter
import id.homebase.resources.photobase_info_title
import kotlinx.datetime.TimeZone
import kotlinx.datetime.asTimeZone
import co.touchlab.kermit.Logger
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt
import kotlin.time.Instant

/**
 * What's known of [item], as Google Photos' info panel shows it: when, where (with its map), the file, the camera,
 * and its [albums]. A section with nothing to say is left out.
 */
@Composable
fun PhotoInfoSheet(
    item: PhotoGalleryItem,
    reader: PhotoInfoReader,
    albums: List<String>,
    /** In this identity's library and not in its Bin, waiting to be deleted. */
    backedUp: Boolean,
    onDismiss: () -> Unit,
) {
    // One per screen: the photo before's reads are dropped when another opens, and all of them when the sheet closes.
    val viewModel = viewModel(key = "photo-info") { PhotoInfoViewModel(reader) }
    LaunchedEffect(viewModel, item.fileId) { viewModel.show(item) }
    DisposableEffect(viewModel) { onDispose { viewModel.close() } }
    val uiState = viewModel.uiState.collectAsStateWithLifecycle().value.takeIf { it.fileId == item.fileId }
    AdaptiveSheet(onDismiss = onDismiss, maxWidth = 480.dp) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            Text(
                stringResource(MR.string.photobase_info_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp),
            )
            WhenRow(item.capturedAtMs, (uiState?.camera as? InfoPart.Read)?.value)
            uiState?.location?.let { at -> WhereSection(at, uiState.place, reader) }
            FileRow(item)
            CameraRow(uiState?.camera ?: InfoPart.Loading)
            if (albums.isNotEmpty()) InfoRow(Icons.Outlined.PhotoAlbum, stringResource(MR.string.photobase_info_albums), joined(albums, MR.string.photobase_info_list))
            if (backedUp) InfoRow(Icons.Outlined.CloudDone, stringResource(MR.string.photobase_info_backed_up), stringResource(MR.string.photobase_info_backed_up_body))
        }
    }
}

// In the zone it was taken in once its EXIF says, as Google Photos shows it; until then, or without one, this device's.
@Composable
private fun WhenRow(capturedAtMs: Long, exif: ImageMetadata?) {
    val (at, captureOffset) = remember(capturedAtMs, exif) { takenAt(capturedAtMs, exif) }
    val system = TimeZone.currentSystemDefault()
    val shown = captureOffset?.let { shiftedTo(at, it, system) } ?: at
    val zone = captureOffset?.asTimeZone() ?: system
    InfoRow(Icons.Outlined.CalendarToday, formatMediumDate(shown), stringResource(MR.string.photobase_info_date_time, formatTime(shown), offsetLabel(at, zone)))
}

@Composable
private fun WhereSection(at: MediaLocation, place: InfoPart<String>, reader: PhotoInfoReader) {
    val uriHandler = LocalUriHandler.current
    val openMap = stringResource(MR.string.photobase_info_open_map)
    val headline = when (place) {
        InfoPart.Failed -> stringResource(MR.string.photobase_info_place_failed)
        InfoPart.Loading -> stringResource(MR.string.photobase_info_location)
        is InfoPart.Read -> place.value ?: stringResource(MR.string.photobase_info_location)
    }
    // With Maps at None the place stays on the device: no map, no lookup and no link.
    if (!reader.mapsOnline) return InfoRow(Icons.Outlined.Place, headline, coordinates(at))
    val open = {
        runCatching { uriHandler.openUri(mapsUrl(at)) }.onFailure { Logger.w(tag = "PhotoInfoSheet", throwable = it) { "maps link not opened" } }
        Unit
    }
    Column(Modifier.clickable(onClickLabel = openMap, role = Role.Button, onClick = open)) {
        PlaceMap(at, reader)
        InfoRow(Icons.Outlined.Place, headline, coordinates(at))
    }
}

@Composable
private fun PlaceMap(at: MediaLocation, reader: PhotoInfoReader) {
    val (x, y) = remember(at) { WebMercator.latLonToUnit(at.latitude, at.longitude) }
    val pin = stringResource(MR.string.cd_location_pin)
    TiledMapView(
        bbox = remember(x, y) { mapBox(x, y) },
        showMapTiles = true,
        fetchTile = reader::tile,
        interactive = false,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().height(180.dp).clip(RoundedCornerShape(12.dp)),
        markerContent = { project, ready ->
            if (ready) {
                Icon(
                    Icons.Filled.LocationOn,
                    contentDescription = pin,
                    tint = MaterialTheme.colorScheme.primary,
                    // The pin's point, at the bottom of the icon, on the photo's place.
                    modifier = Modifier.size(PIN).offset {
                        val point = project(x, y)
                        val size = PIN.toPx()
                        IntOffset((point.x - size / 2).roundToInt(), (point.y - size).roundToInt())
                    },
                )
            }
        },
    )
}

@Composable
private fun FileRow(item: PhotoGalleryItem) {
    val file = remember(item.payload) { fileFacts(item.payload) }
    val details = buildList {
        if (file.width != null && file.height != null) {
            megapixels(file.width, file.height)?.let { add(stringResource(MR.string.photobase_info_megapixels, it)) }
            add(stringResource(MR.string.photobase_info_dimensions, file.width, file.height))
        }
        file.bytes?.let { add(formatBytes(it)) }
    }
    InfoRow(Icons.Outlined.Image, file.name, details)
}

@Composable
private fun CameraRow(camera: InfoPart<ImageMetadata>) {
    when (camera) {
        InfoPart.Loading -> InfoRow(Icons.Outlined.PhotoCamera, stringResource(MR.string.photobase_info_camera_loading), null)
        InfoPart.Failed -> InfoRow(Icons.Outlined.PhotoCamera, stringResource(MR.string.photobase_info_camera_failed), null)
        is InfoPart.Read -> {
            val exif = camera.value ?: return
            val settings = buildList {
                exif.fNumber?.let { add(stringResource(MR.string.photobase_info_aperture, formatDecimal(it, 1))) }
                exif.exposureTimeSeconds?.let { add(stringResource(MR.string.photobase_info_shutter, shutter(it))) }
                exif.focalLengthMm?.let { add(stringResource(MR.string.photobase_info_focal_length, formatDecimal(it, 1))) }
                exif.iso?.let { add(stringResource(MR.string.photobase_info_iso, it)) }
            }
            InfoRow(Icons.Outlined.PhotoCamera, cameraName(exif.cameraMake, exif.cameraModel), settings)
        }
    }
}

// With no title the first detail leads; with neither the row is left out.
@Composable
private fun InfoRow(icon: ImageVector, title: String?, details: List<String>) {
    val headline = title ?: details.firstOrNull() ?: return
    InfoRow(icon, headline, joined(details.drop(if (title == null) 1 else 0), MR.string.photobase_info_joined))
}

// Through a resource, so a language can join them its own way.
@Composable
private fun joined(parts: List<String>, pair: StringResource): String? =
    parts.reduceOrNull { joined, next -> stringResource(pair, joined, next) }

@Composable
private fun InfoRow(icon: ImageVector, headline: String, supporting: String?) {
    ListItem(
        leadingContent = { Icon(icon, contentDescription = null) },
        headlineContent = { Text(headline) },
        supportingContent = supporting?.let { { Text(it) } },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

private val PIN = 32.dp

@Composable
fun InfoButton(onClick: () -> Unit) {
    val label = stringResource(MR.string.photobase_info_open)
    WithTooltip(label) {
        IconButton(onClick = onClick) { Icon(Icons.Outlined.Info, contentDescription = label) }
    }
}
