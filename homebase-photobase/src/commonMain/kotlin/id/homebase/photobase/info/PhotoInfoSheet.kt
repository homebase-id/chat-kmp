package id.homebase.photobase.info

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material.icons.outlined.Person
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
import androidx.compose.runtime.getValue
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
import id.homebase.api.client.drives.files.DescriptorContent
import id.homebase.api.common.OdinId
import id.homebase.api.image.ImageMetadata
import id.homebase.api.media.MediaLocation
import id.homebase.chat.widget.buildGeoUri
import id.homebase.common.util.formatBytes
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
import id.homebase.resources.photobase_info_location
import id.homebase.resources.photobase_info_megapixels
import id.homebase.resources.photobase_info_open
import id.homebase.resources.photobase_info_open_map
import id.homebase.resources.photobase_info_place_failed
import id.homebase.resources.photobase_info_shutter
import id.homebase.resources.photobase_info_title
import id.homebase.resources.photobase_photo_added_by
import kotlinx.datetime.TimeZone
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt
import kotlin.time.Instant

/**
 * What's known of [item], as Google Photos' info panel shows it: when, where (with its map), the file, the camera,
 * its [albums], and, in a shared album, who [addedBy] it. A section with nothing to say is left out.
 */
@Composable
fun PhotoInfoSheet(
    item: PhotoGalleryItem,
    /** Whose album someone shares it in; null in this identity's own library, where it's backed up. */
    owner: OdinId?,
    reader: PhotoInfoReader,
    albums: List<String>,
    /** Who added it to an album someone shares, when not its owner. */
    addedBy: OdinId?,
    onDismiss: () -> Unit,
) {
    val viewModel = viewModel(key = "photo-info-${item.fileId}") { PhotoInfoViewModel(item, owner, reader) }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    AdaptiveSheet(onDismiss = onDismiss, maxWidth = 480.dp) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            Text(
                stringResource(MR.string.photobase_info_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp),
            )
            WhenRow(item.capturedAtMs)
            item.location()?.let { at -> WhereSection(at, uiState.place, viewModel.reader) }
            FileRow(item)
            CameraRow(uiState.camera)
            if (albums.isNotEmpty()) InfoRow(Icons.Outlined.PhotoAlbum, stringResource(MR.string.photobase_info_albums), albums.joinToString(", "))
            if (owner == null) InfoRow(Icons.Outlined.CloudDone, stringResource(MR.string.photobase_info_backed_up), stringResource(MR.string.photobase_info_backed_up_body))
            addedBy?.let { InfoRow(Icons.Outlined.Person, stringResource(MR.string.photobase_photo_added_by, it.domainName), null) }
        }
    }
}

@Composable
private fun WhenRow(capturedAtMs: Long) {
    val at = remember(capturedAtMs) { Instant.fromEpochMilliseconds(capturedAtMs) }
    val time = stringResource(MR.string.photobase_info_date_time, formatTime(at), zoneLabel(at, TimeZone.currentSystemDefault()))
    InfoRow(Icons.Outlined.CalendarToday, formatMediumDate(at), time)
}

@Composable
private fun WhereSection(at: MediaLocation, place: InfoPart<String>, reader: PhotoInfoReader) {
    val uriHandler = LocalUriHandler.current
    val name = (place as? InfoPart.Read)?.value
    val openMap = stringResource(MR.string.photobase_info_open_map)
    val focus = remember(at) { MapFocus(at) }
    val pin = stringResource(MR.string.cd_location_pin)
    Box(
        Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().height(180.dp).clip(RoundedCornerShape(12.dp))
            .clickable(onClickLabel = openMap, role = Role.Button) { uriHandler.openUri(buildGeoUri(at.latitude, at.longitude, name.orEmpty())) },
    ) {
        TiledMapView(
            bbox = focus.bbox,
            showMapTiles = true,
            fetchTile = reader::tile,
            interactive = false,
            modifier = Modifier.fillMaxSize(),
            markerContent = { project, ready ->
                if (ready) {
                    Icon(
                        Icons.Filled.LocationOn,
                        contentDescription = pin,
                        tint = MaterialTheme.colorScheme.primary,
                        // The pin's point, at the bottom of the icon, on the photo's place.
                        modifier = Modifier.size(PIN).offset {
                            val point = project(focus.x, focus.y)
                            val size = PIN.toPx()
                            IntOffset((point.x - size / 2).roundToInt(), (point.y - size).roundToInt())
                        },
                    )
                }
            },
        )
    }
    val headline = when (place) {
        InfoPart.Loading -> stringResource(MR.string.photobase_info_location)
        InfoPart.Failed -> stringResource(MR.string.photobase_info_place_failed)
        is InfoPart.Read -> place.value ?: stringResource(MR.string.photobase_info_place_failed)
    }
    InfoRow(Icons.Outlined.Place, headline, coordinates(at))
}

@Composable
private fun FileRow(item: PhotoGalleryItem) {
    val payload = item.payload
    val (width, height) = when (val info = payload.descriptorInfo()) {
        is DescriptorContent.ImageFile -> info.pixelWidth to info.pixelHeight
        is DescriptorContent.VideoFile -> info.widthPx.takeIf { it > 0 } to info.heightPx.takeIf { it > 0 }
        else -> null to null
    }
    val name = when (val info = payload.descriptorInfo()) {
        is DescriptorContent.ImageFile -> info.name
        is DescriptorContent.VideoFile -> info.name
        else -> null
    }
    val details = buildList {
        if (width != null && height != null) {
            add(stringResource(MR.string.photobase_info_megapixels, megapixels(width, height)))
            add(stringResource(MR.string.photobase_info_dimensions, width, height))
        }
        payload.bytesWritten?.takeIf { it > 0 }?.let { add(formatBytes(it)) }
    }
    if (name == null && details.isEmpty()) return
    InfoRow(Icons.Outlined.Image, name ?: details.first(), details.drop(if (name == null) 1 else 0).joinToString(" · ").ifEmpty { null })
}

@Composable
private fun CameraRow(camera: InfoPart<ImageMetadata>) {
    when (camera) {
        InfoPart.Loading -> InfoRow(Icons.Outlined.PhotoCamera, stringResource(MR.string.photobase_info_camera_loading), null)
        InfoPart.Failed -> InfoRow(Icons.Outlined.PhotoCamera, stringResource(MR.string.photobase_info_camera_failed), null)
        is InfoPart.Read -> {
            val exif = camera.value ?: return
            val settings = buildList {
                exif.fNumber?.let { add(stringResource(MR.string.photobase_info_aperture, aperture(it))) }
                exif.exposureTimeSeconds?.let { add(stringResource(MR.string.photobase_info_shutter, shutter(it))) }
                exif.focalLengthMm?.let { add(stringResource(MR.string.photobase_info_focal_length, focalLength(it))) }
                exif.iso?.let { add(stringResource(MR.string.photobase_info_iso, it)) }
            }
            val camera = cameraName(exif.cameraMake, exif.cameraModel)
            if (camera == null && settings.isEmpty()) return
            InfoRow(Icons.Outlined.PhotoCamera, camera ?: settings.first(), settings.drop(if (camera == null) 1 else 0).joinToString(" · ").ifEmpty { null })
        }
    }
}

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
