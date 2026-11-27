package id.homebase.photobase.info

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddLocationAlt
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Copyright
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.Camera
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Exposure
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.MotionPhotosOn
import androidx.compose.material.icons.outlined.PhotoAlbum
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import co.touchlab.kermit.Logger
import id.homebase.api.client.location.LocationPreview
import id.homebase.api.image.ImageMetadata
import id.homebase.api.image.MediaQuality
import id.homebase.api.media.MediaLocation
import id.homebase.chat.widget.video.playsMotionClips
import id.homebase.common.util.formatBytes
import id.homebase.common.util.formatDecimal
import id.homebase.chat.widget.LocationPreviewCard
import id.homebase.core.util.formatMediumDate
import id.homebase.core.util.formatTime
import id.homebase.core.util.offsetLabel
import id.homebase.core.widget.AdaptiveSheet
import id.homebase.photobase.gallery.PhotoGalleryItem
import id.homebase.photobase.ui.ToolbarAction
import id.homebase.resources.MR
import id.homebase.resources.photobase_info_accuracy
import id.homebase.resources.photobase_info_speed
import id.homebase.resources.photobase_info_gps_time
import id.homebase.resources.photobase_info_lens_serial
import id.homebase.resources.photobase_info_body_serial
import id.homebase.resources.photobase_info_serials
import id.homebase.resources.photobase_info_artist
import id.homebase.resources.photobase_info_description
import id.homebase.resources.photobase_info_subject_distance
import id.homebase.resources.photobase_info_color_wide
import id.homebase.resources.photobase_info_color_srgb
import id.homebase.resources.photobase_info_processed
import id.homebase.resources.photobase_info_scene_night
import id.homebase.resources.photobase_info_scene_portrait
import id.homebase.resources.photobase_info_scene_landscape
import id.homebase.resources.photobase_info_zoom
import id.homebase.resources.photobase_info_focal_35mm
import id.homebase.resources.photobase_info_archived_body
import id.homebase.resources.photobase_info_archived
import id.homebase.resources.photobase_info_uses
import id.homebase.resources.photobase_info_changed_on
import id.homebase.resources.photobase_info_backed_up_on
import id.homebase.resources.photobase_info_add_location
import id.homebase.resources.photobase_info_albums
import id.homebase.resources.photobase_info_altitude_above
import id.homebase.resources.photobase_info_altitude_below
import id.homebase.resources.photobase_info_aperture
import id.homebase.resources.photobase_info_backed_up
import id.homebase.resources.photobase_info_backed_up_body
import id.homebase.resources.photobase_info_camera_failed
import id.homebase.resources.photobase_info_camera_loading
import id.homebase.resources.photobase_info_date_time
import id.homebase.resources.photobase_info_dimensions
import id.homebase.resources.photobase_info_edit_location
import id.homebase.resources.photobase_info_edit_time
import id.homebase.resources.photobase_info_edited_with
import id.homebase.resources.photobase_info_edits_note
import id.homebase.resources.photobase_info_exposure
import id.homebase.resources.photobase_info_exposure_bias
import id.homebase.resources.photobase_info_facing
import id.homebase.resources.photobase_info_flash_fired
import id.homebase.resources.photobase_info_flash_off
import id.homebase.resources.photobase_info_focal_length
import id.homebase.resources.photobase_info_iso
import id.homebase.resources.photobase_info_joined
import id.homebase.resources.photobase_info_lens
import id.homebase.resources.photobase_info_list
import id.homebase.resources.photobase_info_live
import id.homebase.resources.photobase_info_live_body
import id.homebase.resources.photobase_info_live_body_still
import id.homebase.resources.photobase_info_location
import id.homebase.resources.photobase_info_location_details
import id.homebase.resources.photobase_info_megapixels
import id.homebase.resources.photobase_info_metering_average
import id.homebase.resources.photobase_info_metering_center
import id.homebase.resources.photobase_info_metering_multi_spot
import id.homebase.resources.photobase_info_metering_other
import id.homebase.resources.photobase_info_metering_partial
import id.homebase.resources.photobase_info_metering_pattern
import id.homebase.resources.photobase_info_metering_spot
import id.homebase.resources.photobase_info_modified
import id.homebase.resources.photobase_info_open
import id.homebase.resources.photobase_info_place_failed
import id.homebase.resources.photobase_info_program_action
import id.homebase.resources.photobase_info_program_aperture
import id.homebase.resources.photobase_info_program_creative
import id.homebase.resources.photobase_info_program_landscape
import id.homebase.resources.photobase_info_program_manual
import id.homebase.resources.photobase_info_program_normal
import id.homebase.resources.photobase_info_program_portrait
import id.homebase.resources.photobase_info_program_shutter
import id.homebase.resources.photobase_info_quality_untouched
import id.homebase.resources.photobase_info_replay
import id.homebase.resources.photobase_info_save_failed
import id.homebase.resources.photobase_info_shutter
import id.homebase.resources.photobase_info_software
import id.homebase.resources.photobase_info_title
import id.homebase.resources.photobase_info_white_balance_auto
import id.homebase.resources.photobase_info_white_balance_manual
import id.homebase.resources.settings_media_quality_hd
import id.homebase.resources.settings_media_quality_original
import id.homebase.resources.settings_media_quality_standard
import kotlinx.datetime.TimeZone
import kotlinx.datetime.UtcOffset
import kotlinx.datetime.asTimeZone
import kotlinx.datetime.toInstant
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs
import kotlin.time.Instant

/**
 * What's known of [item]: when, where (with its map), the file, the camera, how it was edited, and its [albums]. A
 * section with nothing to say is left out. With [editable], the time and the place can be changed.
 */
@Composable
fun PhotoInfoSheet(
    item: PhotoGalleryItem,
    source: PhotoInfoSource,
    albums: List<String>,
    backedUp: Boolean,
    editable: Boolean,
    onDismiss: () -> Unit,
) {
    // One per screen: the photo before's reads are dropped when another opens, and all of them when the sheet closes.
    val viewModel = viewModel(key = "photo-info") { PhotoInfoViewModel(source) }
    LaunchedEffect(viewModel, item.fileId) { viewModel.show(item) }
    DisposableEffect(viewModel) { onDispose { viewModel.close() } }
    val uiState = viewModel.uiState.collectAsStateWithLifecycle().value.takeIf { it.fileId == item.fileId }
    val exif = (uiState?.camera as? InfoPart.Read)?.value
    var editing by remember { mutableStateOf<Editing?>(null) }
    val header = remember(item, uiState) { uiState?.let { HeaderFacts(it.capturedAtMs, it.timeByHand, it.offsetByHand, it.location, it.placeByHand) } ?: item.headerFacts() }
    val taken = remember(header, exif) { takenAt(header.capturedAtMs, exif, header.timeByHand, header.offsetByHand) }
    val canEdit = editable && uiState != null && !uiState.saving
    // Only once the EXIF is read: its zone is the clock the time is picked on, and a failed read would lose it.
    val canEditTime = canEdit && uiState?.camera is InfoPart.Read
    AdaptiveSheet(onDismiss = onDismiss, maxWidth = 480.dp) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            Text(
                stringResource(MR.string.photobase_info_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp),
            )
            if (uiState?.saving == true) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 24.dp))
            if (uiState?.saveFailed == true) {
                Text(
                    stringResource(MR.string.photobase_info_save_failed),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                )
            }
            WhenRow(taken, onEdit = { editing = Editing.Time(taken.first, taken.second) }.takeIf { canEditTime })
            val location = uiState?.location
            if (location != null) {
                WhereSection(location, uiState.place, source, onEdit = { editing = Editing.Place }.takeIf { canEdit })
            } else if (canEdit) {
                InfoRow(Icons.Outlined.AddLocationAlt, stringResource(MR.string.photobase_info_add_location), null, Modifier.clickable { editing = Editing.Place })
            }
            location?.let { LocationDetailsRow(it, exif.takeUnless { header.placeByHand }) }
            FileRow(item)
            if (item.motion != null) LiveRow()
            CameraSection(uiState?.camera ?: InfoPart.Loading)
            exif?.let {
                EditedRow(it)
                AboutRows(it)
            }
            if (albums.isNotEmpty()) InfoRow(Icons.Outlined.PhotoAlbum, stringResource(MR.string.photobase_info_albums), joined(albums, MR.string.photobase_info_list))
            if (item.archived) InfoRow(Icons.Outlined.Archive, stringResource(MR.string.photobase_info_archived), stringResource(MR.string.photobase_info_archived_body))
            if (backedUp) BackedUpRow(item)
            if (editable) {
                Text(
                    stringResource(MR.string.photobase_info_edits_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
            }
        }
    }
    when (val opened = editing) {
        is Editing.Time -> {
            EditTimeDialogs(
                at = opened.at,
                zone = opened.offset?.asTimeZone() ?: TimeZone.currentSystemDefault(),
                onSave = { newAt ->
                    editing = null
                    // A photo with no zone keeps none: the device's is only the clock it was picked on.
                    viewModel.save(PhotoEdit.Time(newAt, opened.offset))
                },
                onDismiss = { editing = null },
            )
        }
        Editing.Place -> EditPlaceDialog(
            at = uiState?.location,
            source = source,
            onSave = {
                editing = null
                viewModel.save(PhotoEdit.Place(it))
            },
            onDismiss = { editing = null },
        )
        null -> Unit
    }
}

private sealed interface Editing {
    /** The time and zone as the dialog opened on them. */
    data class Time(val at: Instant, val offset: UtcOffset?) : Editing

    data object Place : Editing
}

// In the zone it was taken in once known; until then, or without one, this device's.
@Composable
private fun WhenRow(taken: Pair<Instant, UtcOffset?>, onEdit: (() -> Unit)?) {
    val (at, captureOffset) = taken
    val system = TimeZone.currentSystemDefault()
    val shown = captureOffset?.let { shiftedTo(at, it, system) } ?: at
    val zone = captureOffset?.asTimeZone() ?: system
    InfoRow(
        Icons.Outlined.CalendarToday,
        formatMediumDate(shown),
        stringResource(MR.string.photobase_info_date_time, formatTime(shown), offsetLabel(at, zone)),
        trailing = onEdit?.let { { EditButton(MR.string.photobase_info_edit_time, it) } },
    )
}

@Composable
private fun WhereSection(at: MediaLocation, place: InfoPart<LocationPreview>, source: PhotoInfoSource, onEdit: (() -> Unit)?) {
    val trailing: (@Composable () -> Unit)? = onEdit?.let { { EditButton(MR.string.photobase_info_edit_location, it) } }
    // With Maps at None the place stays on the device: no map, no lookup and no link.
    if (!source.mapsOnline) return InfoRow(Icons.Outlined.Place, stringResource(MR.string.photobase_info_location), coordinates(at), trailing = trailing)
    val uriHandler = LocalUriHandler.current
    val open = {
        runCatching { uriHandler.openUri(mapsUrl(at)) }.onFailure { Logger.w(tag = "PhotoInfoSheet", throwable = it) { "maps link not opened" } }
        Unit
    }
    // Until it's read, the card holds a pin and the coordinates.
    val preview = (place as? InfoPart.Read)?.value ?: LocationPreview(at.latitude, at.longitude, coordinates(at), imageUrl = null, imageWidth = null, imageHeight = null)
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth(),
    ) {
        LocationPreviewCard(preview, onOpen = open)
    }
    val failed = (place as? InfoPart.Failed)?.let { stringResource(MR.string.photobase_info_place_failed) }
    InfoRow(Icons.Outlined.Place, coordinates(at), failed, trailing = trailing)
}

@Composable
private fun LocationDetailsRow(location: MediaLocation, exif: ImageMetadata?) {
    val details = buildList {
        location.altitude?.let {
            val meters = formatDecimal(abs(it), 0)
            add(stringResource(if (it < 0) MR.string.photobase_info_altitude_below else MR.string.photobase_info_altitude_above, meters))
        }
        exif?.imageDirectionDegrees?.let { add(stringResource(MR.string.photobase_info_facing, formatDecimal(it, 0))) }
        exif?.horizontalErrorMeters?.let { add(stringResource(MR.string.photobase_info_accuracy, formatDecimal(it, 0))) }
        exif?.speedKmh?.let { add(stringResource(MR.string.photobase_info_speed, formatDecimal(it, 0))) }
        exif?.gpsTime?.let { add(stringResource(MR.string.photobase_info_gps_time, stringResource(MR.string.photobase_info_date_time, formatMediumDate(it), formatTime(it)))) }
    }
    if (details.isNotEmpty()) InfoRow(Icons.Outlined.Explore, stringResource(MR.string.photobase_info_location_details), details)
}

@Composable
private fun FileRow(item: PhotoGalleryItem) {
    val file = remember(item.payload) { fileFacts(item.payload) }
    val details = buildList {
        file.type?.let { add(it) }
        qualityLabel(file.quality, file.untouched)?.let { add(it) }
        if (file.width != null && file.height != null) {
            megapixels(file.width, file.height)?.let { add(stringResource(MR.string.photobase_info_megapixels, it)) }
            add(stringResource(MR.string.photobase_info_dimensions, file.width, file.height))
        }
        file.bytes?.let { add(formatBytes(it)) }
    }
    InfoRow(Icons.Outlined.Image, file.name, details)
}

@Composable
private fun qualityLabel(quality: MediaQuality?, untouched: Boolean): String? = when {
    untouched -> stringResource(MR.string.photobase_info_quality_untouched)
    quality == MediaQuality.ORIGINAL -> stringResource(MR.string.settings_media_quality_original)
    quality == MediaQuality.HD -> stringResource(MR.string.settings_media_quality_hd)
    quality == MediaQuality.STANDARD -> stringResource(MR.string.settings_media_quality_standard)
    else -> null
}

@Composable
private fun LiveRow() {
    InfoRow(
        Icons.Outlined.MotionPhotosOn,
        stringResource(MR.string.photobase_info_live),
        stringResource(if (playsMotionClips) MR.string.photobase_info_live_body else MR.string.photobase_info_live_body_still),
    )
}

@Composable
private fun CameraSection(camera: InfoPart<ImageMetadata>) {
    when (camera) {
        InfoPart.Loading -> InfoRow(Icons.Outlined.PhotoCamera, stringResource(MR.string.photobase_info_camera_loading), null)
        InfoPart.Failed -> InfoRow(Icons.Outlined.PhotoCamera, stringResource(MR.string.photobase_info_camera_failed), null)
        is InfoPart.Read -> {
            val exif = camera.value ?: return
            val settings = buildList {
                exif.fNumber?.let { add(stringResource(MR.string.photobase_info_aperture, formatDecimal(it, 1))) }
                exif.exposureTimeSeconds?.let { add(stringResource(MR.string.photobase_info_shutter, shutter(it))) }
                exif.focalLengthMm?.let { add(stringResource(MR.string.photobase_info_focal_length, formatDecimal(it, 1))) }
                exif.focalLength35mm?.let { add(stringResource(MR.string.photobase_info_focal_35mm, it)) }
                exif.digitalZoomRatio?.takeIf { it > 1.0 }?.let { add(stringResource(MR.string.photobase_info_zoom, formatDecimal(it, 1))) }
                exif.iso?.let { add(stringResource(MR.string.photobase_info_iso, it)) }
            }
            InfoRow(Icons.Outlined.PhotoCamera, cameraName(exif.cameraMake, exif.cameraModel), settings)
            cameraName(exif.lensMake, exif.lensModel)?.let { InfoRow(Icons.Outlined.Camera, it, stringResource(MR.string.photobase_info_lens)) }
            val exposure = buildList {
                exif.exposureBiasEv?.let { add(stringResource(MR.string.photobase_info_exposure_bias, exposureBias(it))) }
                exif.exposureProgram?.let { programName(it) }?.let { add(stringResource(it)) }
                exif.meteringMode?.let { meteringName(it) }?.let { add(stringResource(it)) }
                exif.flash?.let { add(stringResource(if (it and 1 == 1) MR.string.photobase_info_flash_fired else MR.string.photobase_info_flash_off)) }
                exif.whiteBalance?.let { add(stringResource(if (it == 1) MR.string.photobase_info_white_balance_manual else MR.string.photobase_info_white_balance_auto)) }
                exif.sceneCaptureType?.let { sceneName(it) }?.let { add(stringResource(it)) }
                if (exif.customRendered == 1) add(stringResource(MR.string.photobase_info_processed))
                exif.colorSpace?.let { add(stringResource(if (it == 1) MR.string.photobase_info_color_srgb else MR.string.photobase_info_color_wide)) }
                exif.subjectDistanceMeters?.let { add(stringResource(MR.string.photobase_info_subject_distance, formatDecimal(it, 1))) }
            }
            if (exposure.isNotEmpty()) InfoRow(Icons.Outlined.Exposure, stringResource(MR.string.photobase_info_exposure), exposure)
        }
    }
}

// "Edited with" only when the file was saved after it was taken: phones write their own version as the software.
@Composable
private fun EditedRow(exif: ImageMetadata) {
    val modified = exif.modifiedAt?.takeIf { exif.capturedAt != null && it != exif.capturedAt }
    // EXIF's clock has no zone: read in this device's, it shows as written.
    val modifiedText = modified?.let { stringResource(MR.string.photobase_info_modified, formatMediumDate(it.toInstant(TimeZone.currentSystemDefault()))) }
    val software = exif.software
    when {
        software != null && modifiedText != null -> InfoRow(Icons.Outlined.AutoFixHigh, stringResource(MR.string.photobase_info_edited_with, software), modifiedText)
        modifiedText != null -> InfoRow(Icons.Outlined.AutoFixHigh, modifiedText, null)
        software != null -> InfoRow(Icons.Outlined.Memory, software, stringResource(MR.string.photobase_info_software))
    }
}

// The words, credit and serials a camera or editor wrote into it.
@Composable
private fun AboutRows(exif: ImageMetadata) {
    val words = listOfNotNull(exif.imageDescription, exif.userComment).distinct()
    if (words.isNotEmpty()) InfoRow(Icons.AutoMirrored.Outlined.Notes, words.first(), joined(words.drop(1), MR.string.photobase_info_joined) ?: stringResource(MR.string.photobase_info_description))
    val credit = listOfNotNull(exif.artist?.let { stringResource(MR.string.photobase_info_artist, it) }, exif.copyright)
    InfoRow(Icons.Outlined.Copyright, null, credit)
    val serials = listOfNotNull(
        exif.bodySerial?.let { stringResource(MR.string.photobase_info_body_serial, it) },
        exif.lensSerial?.let { stringResource(MR.string.photobase_info_lens_serial, it) },
    )
    if (serials.isNotEmpty()) InfoRow(Icons.Outlined.Tag, stringResource(MR.string.photobase_info_serials), serials)
}

// When it reached your Homebase, when its record there last changed, and the space all its copies take.
@Composable
private fun BackedUpRow(item: PhotoGalleryItem) {
    val details = buildList {
        if (item.backedUpAtMs > 0) add(stringResource(MR.string.photobase_info_backed_up_on, formatMediumDate(Instant.fromEpochMilliseconds(item.backedUpAtMs))))
        // A minute's grace: the backup itself writes the record more than once.
        if (item.changedAtMs > item.backedUpAtMs + 60_000) add(stringResource(MR.string.photobase_info_changed_on, formatMediumDate(Instant.fromEpochMilliseconds(item.changedAtMs))))
        item.storedBytes?.let { add(stringResource(MR.string.photobase_info_uses, formatBytes(it))) }
    }
    InfoRow(Icons.Outlined.CloudDone, stringResource(MR.string.photobase_info_backed_up), joined(details, MR.string.photobase_info_joined) ?: stringResource(MR.string.photobase_info_backed_up_body))
}

private fun sceneName(code: Int): StringResource? = when (code) {
    1 -> MR.string.photobase_info_scene_landscape
    2 -> MR.string.photobase_info_scene_portrait
    3 -> MR.string.photobase_info_scene_night
    else -> null
}

private fun programName(code: Int): StringResource? = when (code) {
    1 -> MR.string.photobase_info_program_manual
    2 -> MR.string.photobase_info_program_normal
    3 -> MR.string.photobase_info_program_aperture
    4 -> MR.string.photobase_info_program_shutter
    5 -> MR.string.photobase_info_program_creative
    6 -> MR.string.photobase_info_program_action
    7 -> MR.string.photobase_info_program_portrait
    8 -> MR.string.photobase_info_program_landscape
    else -> null
}

private fun meteringName(code: Int): StringResource? = when (code) {
    1 -> MR.string.photobase_info_metering_average
    2 -> MR.string.photobase_info_metering_center
    3 -> MR.string.photobase_info_metering_spot
    4 -> MR.string.photobase_info_metering_multi_spot
    5 -> MR.string.photobase_info_metering_pattern
    6 -> MR.string.photobase_info_metering_partial
    255 -> MR.string.photobase_info_metering_other
    else -> null
}

@Composable
private fun EditButton(label: StringResource, onClick: () -> Unit) {
    val text = stringResource(label)
    IconButton(onClick = onClick) { Icon(Icons.Outlined.Edit, contentDescription = text) }
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
private fun InfoRow(
    icon: ImageVector,
    headline: String,
    supporting: String?,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    ListItem(
        leadingContent = { Icon(icon, contentDescription = null) },
        headlineContent = { Text(headline) },
        supportingContent = supporting?.let { { Text(it) } },
        trailingContent = trailing,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = modifier,
    )
}


@Composable
fun InfoButton(onClick: () -> Unit) {
    ToolbarAction(Icons.Outlined.Info, MR.string.photobase_info_open, enabled = true, onClick)
}

@Composable
fun LiveButton(onClick: () -> Unit) {
    ToolbarAction(Icons.Outlined.MotionPhotosOn, MR.string.photobase_info_replay, enabled = true, onClick)
}
