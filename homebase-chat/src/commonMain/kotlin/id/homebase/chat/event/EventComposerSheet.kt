package id.homebase.chat.event

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import id.homebase.api.client.location.LocationPreviewProvider
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.util.truncateToCodePoints
import id.homebase.chat.composer.ComposerEditableField
import id.homebase.chat.composer.ComposerRow
import id.homebase.chat.composer.ComposerTitleField
import id.homebase.chat.conversationlist.materializeForUpload
import id.homebase.core.location.rememberCurrentGps
import id.homebase.core.location.tracking.GpsFixResult
import id.homebase.chat.services.ChatMessageSenderService
import id.homebase.chat.services.ChatProtocol
import id.homebase.chat.services.builder.AttachmentInput
import id.homebase.chat.services.builder.MessageAttachmentBuilder
import id.homebase.chat.services.builder.toImageAttachmentInput
import id.homebase.chat.services.content.MessageContent
import id.homebase.core.ui.theme.HomebaseTheme
import id.homebase.core.util.rememberCameraManager
import id.homebase.resources.MR
import id.homebase.resources.cancel
import id.homebase.resources.close
import id.homebase.resources.ok
import id.homebase.resources.chat_event_description_hint
import id.homebase.resources.chat_event_duration_custom_end
import id.homebase.resources.chat_event_duration_hours
import id.homebase.resources.chat_event_duration_hours_minutes
import id.homebase.resources.chat_event_duration_minutes
import id.homebase.resources.chat_event_duration_title
import id.homebase.resources.chat_event_ends_at
import id.homebase.resources.chat_event_location_hint
import id.homebase.resources.chat_event_meeting_url_hint
import id.homebase.resources.chat_event_send
import id.homebase.resources.chat_event_add_photo
import id.homebase.resources.chat_event_cover_photo
import id.homebase.resources.chat_event_remove_photo
import id.homebase.resources.chat_event_title_hint
import id.homebase.resources.chat_event_use_current_location
import id.homebase.resources.vault_image_choose_gallery
import id.homebase.resources.vault_image_take_photo
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

private const val MAX_TITLE_CODEPOINTS = 200
private const val MAX_DESCRIPTION_CODEPOINTS = 4000

/** Which cover-photo source the deferred [LaunchedEffect] should present. */
private enum class PhotoSource { Gallery, Camera }

/**
 * Bottom-sheet composer for an Event message, modeled on the Google Calendar
 * quick-create screen: a borderless title, then flat icon-led rows.
 *
 * Presentation only — [EventDescriptor], the wire format and the send path are
 * unchanged. State is local `remember` (the composer is short-lived:
 * open → fill → send/dismiss).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventComposerSheet(
    conversationId: Uuid,
    onDismiss: () -> Unit,
    onSent: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        EventComposerContent(
            conversationId = conversationId,
            sheetState = sheetState,
            onDismiss = onDismiss,
            onSent = onSent,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalUuidApi::class)
@Composable
private fun EventComposerContent(
    conversationId: Uuid,
    sheetState: SheetState,
    onDismiss: () -> Unit,
    onSent: () -> Unit,
) {
    val sender: ChatMessageSenderService = koinInject()
    val scope = rememberCoroutineScope()
    val brand = HomebaseTheme.extendedColors.bubbleSentSurface

    val fileOperationsProvider: FileOperationsProvider = koinInject()
    // Optional cover photo. Resolved to an AttachmentInput at pick time (the iOS
    // gallery security scope dies before send — materialize copies into the
    // sandbox now), so send just builds the bundle. Null = no photo.
    var coverInput by remember { mutableStateOf<AttachmentInput?>(null) }
    var showPhotoMenu by remember { mutableStateOf(false) }
    val coverGalleryPicker = rememberFilePickerLauncher(type = FileKitType.Image) { file ->
        if (file != null) scope.launch {
            coverInput = file.materializeForUpload(fileOperationsProvider)
                .toImageAttachmentInput(fileOperationsProvider)
        }
    }
    val coverCameraLauncher = rememberCameraManager { file ->
        if (file != null) scope.launch {
            coverInput = file.materializeForUpload(fileOperationsProvider)
                .toImageAttachmentInput(fileOperationsProvider)
        }
    }
    // Deferred picker launch — presenting the picker synchronously from the
    // DropdownMenu's onClick wedges the menu open on iOS (it can't dismiss while a
    // UIViewController is presenting over it). Closing the menu sets this pending
    // action; the LaunchedEffect then launches once the menu has dismissed (mirrors
    // VaultScreen's deferred picker, PR #760).
    var pendingPhotoAction by remember { mutableStateOf<PhotoSource?>(null) }
    LaunchedEffect(pendingPhotoAction) {
        when (pendingPhotoAction) {
            PhotoSource.Gallery -> coverGalleryPicker.launch()
            PhotoSource.Camera -> coverCameraLauncher.launch()
            null -> {}
        }
        pendingPhotoAction = null
    }

    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    val systemTz = remember { TimeZone.currentSystemDefault() }
    var timezone by remember { mutableStateOf(systemTz.id) }

    // Default window: start = top of the next hour (always in the future, rolls past
    // midnight), end = start + 1h. Never collapses to start==end — the old hour-clamp did
    // at/after 22:00, which left Send disabled and the chip at "0m" (#786).
    val (defaultStart, defaultEnd) = remember { defaultEventWindow(Clock.System.now(), systemTz) }
    var startDateTime by remember { mutableStateOf(defaultStart) }
    var endDateTime by remember { mutableStateOf(defaultEnd) }
    var locationText by remember { mutableStateOf("") }
    var locationLat by remember { mutableStateOf<Double?>(null) }
    var locationLon by remember { mutableStateOf<Double?>(null) }
    var meetingUrl by remember { mutableStateOf("") }
    var fetchingLocation by remember { mutableStateOf(false) }

    val locationPreviewProvider: LocationPreviewProvider = koinInject()
    val currentLocationLauncher = rememberCurrentGps { result ->
        when (result) {
            is GpsFixResult.Success -> {
                scope.launch {
                    val preview = runCatching {
                        locationPreviewProvider.getLocationPreview(
                            result.point.lat, result.point.lon,
                        )
                    }.getOrNull()
                    locationText = preview?.address?.takeIf { it.isNotBlank() }
                        ?: "${result.point.lat}, ${result.point.lon}"
                    locationLat = result.point.lat
                    locationLon = result.point.lon
                    fetchingLocation = false
                }
            }
            is GpsFixResult.PermissionDenied,
            is GpsFixResult.Unavailable,
            is GpsFixResult.Timeout -> {
                fetchingLocation = false
            }
        }
    }

    var sending by remember { mutableStateOf(false) }
    var showStartDate by remember { mutableStateOf(false) }
    var showStartTime by remember { mutableStateOf(false) }
    var showEndDate by remember { mutableStateOf(false) }
    var showEndTime by remember { mutableStateOf(false) }
    var showDurationPicker by remember { mutableStateOf(false) }
    var tzExpanded by remember { mutableStateOf(false) }

    // Every event always has an end (default start + 1h); the picker callbacks clamp so
    // end stays strictly after start. This is a defensive guard for Send.
    val timesValid by remember {
        derivedStateOf {
            // LocalDateTime is Comparable; wall-clock ordering is what matters and is
            // zone-independent, so compare directly instead of round-tripping via a zone.
            startDateTime < endDateTime
        }
    }
    val isValid by remember {
        derivedStateOf { title.isNotBlank() && !sending && timesValid }
    }

    val dismiss: () -> Unit = {
        scope.launch {
            sheetState.hide()
            onDismiss()
        }
    }

    val doSend: () -> Unit = {
        if (isValid) {
            sending = true
            scope.launch {
                val tz = runCatching { TimeZone.of(timezone) }.getOrDefault(systemTz)
                // If the start drifted into the past while the sheet was open, snap it to
                // now (shifting the end to preserve the duration) so no past event is sent.
                val effectiveStart = clampStartToNow(startDateTime, tz, Clock.System.now())
                val effectiveEnd = shiftEndPreservingDuration(startDateTime, effectiveStart, endDateTime, tz)
                val startUtcMs = effectiveStart.toInstant(tz).toEpochMilliseconds()
                val endUtcMs = effectiveEnd.toInstant(tz).toEpochMilliseconds()
                val descriptor = EventDescriptor(
                    title = title.truncateToCodePoints(MAX_TITLE_CODEPOINTS),
                    description = description.truncateToCodePoints(MAX_DESCRIPTION_CODEPOINTS),
                    startUtcMs = startUtcMs,
                    endUtcMs = endUtcMs,
                    timezone = tz.id,
                    locationText = locationText.takeIf { it.isNotBlank() },
                    lat = locationLat,
                    lon = locationLon,
                    meetingUrl = meetingUrl.takeIf { it.isNotBlank() },
                )
                val bundle = coverInput?.let { input ->
                    MessageAttachmentBuilder.buildSingle(
                        attachment = input,
                        fileOperationsProvider = fileOperationsProvider,
                        payloadKey = ChatProtocol.PAYLOAD_KEY_MESSAGE_WEB + "0",
                    )
                }
                runCatching {
                    sender.sendNewTypedMessage(
                        messageUniqueId = Uuid.random(),
                        conversationId = conversationId,
                        content = MessageContent.Event(descriptor),
                        previousMessageUniqueId = null,
                        payloadBundle = bundle,
                    )
                }
                sheetState.hide()
                onSent()
            }
        }
    }

    Column(modifier = Modifier.fillMaxWidth().fillMaxHeight(0.92f)) {
        // Top bar: close (start) · Send (end).
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 16.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = dismiss) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(MR.string.close),
                )
            }
            Spacer(Modifier.weight(1f))
            Button(
                onClick = doSend,
                enabled = isValid,
                colors = ButtonDefaults.buttonColors(
                    containerColor = brand,
                    contentColor = HomebaseTheme.extendedColors.bubbleSentOnSurface,
                ),
            ) {
                Text(stringResource(MR.string.chat_event_send))
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                // imePadding INSIDE the scroll pads the content (not the viewport) so
                // the focused field scrolls above the keyboard. The ModalBottomSheet
                // already lifts for the IME, so shrinking the viewport here would
                // double-count and collapse the sheet on Android. Matches
                // ConnectRequestBottomSheet / VaultNewSectionSheet.
                .imePadding()
                .padding(horizontal = 20.dp),
        ) {
            // Optional cover photo — picked from gallery/camera, attached as a
            // chat_web0 image payload and rendered above the event card. Empty
            // state shows an "Add photo" button; filled state shows a rounded
            // preview with a remove button.
            val cover = coverInput
            if (cover != null) {
                Box(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    AsyncImage(
                        model = cover.filePath,
                        contentDescription = stringResource(MR.string.chat_event_cover_photo),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp)
                            .clip(RoundedCornerShape(12.dp)),
                        contentScale = ContentScale.Crop,
                    )
                    IconButton(
                        onClick = { coverInput = null },
                        modifier = Modifier.align(Alignment.TopEnd),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(MR.string.chat_event_remove_photo),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
            } else {
                OutlinedButton(
                    onClick = { showPhotoMenu = true },
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.AddPhotoAlternate,
                        contentDescription = null,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(MR.string.chat_event_add_photo))
                }
                Spacer(Modifier.height(12.dp))
            }
            if (showPhotoMenu) {
                PhotoSourceDialog(
                    onPick = {
                        showPhotoMenu = false
                        pendingPhotoAction = it
                    },
                    onDismiss = { showPhotoMenu = false },
                )
            }

            // Title — borderless headline with a brand-blue underline indicator.
            ComposerTitleField(
                value = title,
                onValueChange = { title = it },
                placeholder = stringResource(MR.string.chat_event_title_hint),
                brand = brand,
            )

            Spacer(Modifier.height(12.dp))

            // WHEN group — start "date · time" line, a hyperlinked duration beneath it,
            // and the timezone row. Every event has an end (default start + 1h); tapping
            // the duration opens the duration/end picker (#786).
            ComposerRow(icon = Icons.Default.Schedule, filled = true, verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    DateTimeLine(
                        local = startDateTime,
                        onPickDate = { showStartDate = true },
                        onPickTime = { showStartTime = true },
                    )
                    AssistChip(
                        onClick = { showDurationPicker = true },
                        label = { Text(eventDurationLabel(startDateTime, endDateTime, systemTz)) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.HourglassEmpty,
                                contentDescription = null,
                                modifier = Modifier.size(AssistChipDefaults.IconSize),
                            )
                        },
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            ComposerRow(
                icon = Icons.Default.Public,
                filled = true,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(onClick = { tzExpanded = true }),
            ) {
                Text(
                    text = friendlyZoneLabel(timezone),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // Meeting link.
            ComposerRow(icon = Icons.Default.Videocam, filled = meetingUrl.isNotEmpty()) {
                ComposerEditableField(
                    value = meetingUrl,
                    onValueChange = { meetingUrl = it },
                    placeholder = stringResource(MR.string.chat_event_meeting_url_hint),
                    singleLine = true,
                    keyboardType = KeyboardType.Uri,
                    cursorColor = brand,
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // Location, with a current-location trailing action.
            ComposerRow(
                icon = Icons.Default.LocationOn,
                filled = locationText.isNotEmpty(),
                trailing = {
                    IconButton(
                        enabled = !fetchingLocation,
                        onClick = {
                            fetchingLocation = true
                            currentLocationLauncher.launch()
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Default.MyLocation,
                            contentDescription = stringResource(MR.string.chat_event_use_current_location),
                        )
                    }
                },
            ) {
                ComposerEditableField(
                    value = locationText,
                    onValueChange = {
                        locationText = it
                        // The user is hand-editing the address, so previously captured
                        // coordinates no longer correspond. Drop them.
                        locationLat = null
                        locationLon = null
                    },
                    placeholder = stringResource(MR.string.chat_event_location_hint),
                    singleLine = true,
                    cursorColor = brand,
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            // Description.
            ComposerRow(
                icon = Icons.AutoMirrored.Filled.Notes,
                filled = description.isNotEmpty(),
                verticalAlignment = Alignment.Top,
            ) {
                ComposerEditableField(
                    value = description,
                    onValueChange = { description = it },
                    placeholder = stringResource(MR.string.chat_event_description_hint),
                    singleLine = false,
                    cursorColor = brand,
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    if (tzExpanded) {
        TimezonePickerSheet(
            currentZoneId = timezone,
            onPick = { picked ->
                timezone = picked
                tzExpanded = false
            },
            onDismiss = { tzExpanded = false },
        )
    }

    if (showStartDate) {
        DatePickerSheet(
            initial = startDateTime,
            // No past days: lower-bound the picker at today (start-of-day, UTC frame).
            minDateMillis = remember(systemTz) { startOfTodayUtcMillis(Clock.System.now(), systemTz) },
            onConfirm = { date ->
                val picked = LocalDateTime(date.year, date.month, date.day, startDateTime.hour, startDateTime.minute)
                // A past pick snaps to the current time — events never start in the past.
                val newStart = clampStartToNow(picked, systemTz, Clock.System.now())
                endDateTime = shiftEndPreservingDuration(startDateTime, newStart, endDateTime, systemTz)
                startDateTime = newStart
                showStartDate = false
            },
            onDismiss = { showStartDate = false },
        )
    }
    if (showStartTime) {
        TimePickerSheet(
            initial = LocalTime(startDateTime.hour, startDateTime.minute),
            onConfirm = { time ->
                val picked = LocalDateTime(startDateTime.year, startDateTime.month, startDateTime.day, time.hour, time.minute)
                // A past pick snaps to the current time — events never start in the past.
                val newStart = clampStartToNow(picked, systemTz, Clock.System.now())
                endDateTime = shiftEndPreservingDuration(startDateTime, newStart, endDateTime, systemTz)
                startDateTime = newStart
                showStartTime = false
            },
            onDismiss = { showStartTime = false },
        )
    }
    if (showEndDate) {
        DatePickerSheet(
            initial = endDateTime,
            onConfirm = { date ->
                val newEnd = LocalDateTime(date.year, date.month, date.day, endDateTime.hour, endDateTime.minute)
                endDateTime = clampEndAfterStart(startDateTime, newEnd, systemTz)
                showEndDate = false
                // "Set end date and time" is a single flow — chain straight into the time
                // picker once the date is chosen, so the user can set both.
                showEndTime = true
            },
            onDismiss = { showEndDate = false },
        )
    }
    if (showEndTime) {
        TimePickerSheet(
            initial = LocalTime(endDateTime.hour, endDateTime.minute),
            onConfirm = { time ->
                val newEnd = LocalDateTime(endDateTime.year, endDateTime.month, endDateTime.day, time.hour, time.minute)
                endDateTime = clampEndAfterStart(startDateTime, newEnd, systemTz)
                showEndTime = false
            },
            onDismiss = { showEndTime = false },
        )
    }
    if (showDurationPicker) {
        DurationPickerSheet(
            start = startDateTime,
            end = endDateTime,
            tz = systemTz,
            onPickDuration = { minutes ->
                endDateTime = startDateTime.toInstant(systemTz).plus(minutes.minutes).toLocalDateTime(systemTz)
                showDurationPicker = false
            },
            onPickCustomEnd = {
                showDurationPicker = false
                showEndDate = true
            },
            onDismiss = { showDurationPicker = false },
        )
    }
}

/**
 * New start chosen — shift [end] by the same delta so the duration is preserved
 * (Google Calendar behavior). Always keeps end strictly after start.
 */
private fun shiftEndPreservingDuration(
    oldStart: LocalDateTime,
    newStart: LocalDateTime,
    end: LocalDateTime,
    tz: TimeZone,
): LocalDateTime {
    val delta = newStart.toInstant(tz) - oldStart.toInstant(tz)
    return end.toInstant(tz).plus(delta).toLocalDateTime(tz)
}

/**
 * End chosen — clamp so it stays strictly after [start]. If the picked end is at or
 * before start, snap it to start + 1h (a sensible minimum), matching the default
 * one-hour event length.
 */
private fun clampEndAfterStart(
    start: LocalDateTime,
    end: LocalDateTime,
    tz: TimeZone,
): LocalDateTime {
    val startInstant = start.toInstant(tz)
    return if (end.toInstant(tz) > startInstant) end
    else startInstant.plus(1.hours).toLocalDateTime(tz)
}

/** One "date · time" line where the date and the time are independently tappable. */
@Composable
private fun DateTimeLine(
    local: LocalDateTime,
    onPickDate: () -> Unit,
    onPickTime: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = formatFriendlyDate(local),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).clickable(onClick = onPickDate).padding(vertical = 6.dp),
        )
        Text(
            text = formatTime(local),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onPickTime).padding(vertical = 6.dp, horizontal = 4.dp),
        )
    }
}

/** e.g. "Tue, Jun 16, 2026". */
private fun formatFriendlyDate(d: LocalDateTime): String {
    val dow = d.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }.take(3)
    val mon = d.month.name.lowercase().replaceFirstChar { it.uppercase() }.take(3)
    return "$dow, $mon ${d.day}, ${d.year}"
}

/** 24h "HH:mm". */
private fun formatTime(d: LocalDateTime): String =
    d.hour.toString().padStart(2, '0') + ":" + d.minute.toString().padStart(2, '0')

/**
 * Pure split of an event's [start]→[end] span: total minutes and whether the two fall on
 * the same calendar day. Drives the composer's duration label; unit-tested in
 * EventDurationPartsTest.
 */
internal data class EventDurationParts(val totalMinutes: Long, val sameDay: Boolean)

internal fun eventDurationParts(
    start: LocalDateTime,
    end: LocalDateTime,
    tz: TimeZone,
): EventDurationParts =
    EventDurationParts(
        totalMinutes = (end.toInstant(tz) - start.toInstant(tz)).inWholeMinutes,
        sameDay = start.date == end.date,
    )

/**
 * Default start/end for a fresh composer: start = top of the next hour (always strictly
 * after [now], rolling past midnight), end = start + 1h. Instant math, so it never collapses
 * to start==end the way the old `(hour+1).coerceAtMost(23)` did at/after 22:00. Unit-tested
 * in EventStartValidationTest.
 */
internal fun defaultEventWindow(now: Instant, tz: TimeZone): Pair<LocalDateTime, LocalDateTime> {
    val local = now.toLocalDateTime(tz)
    val start = LocalDateTime(local.year, local.month, local.day, local.hour, 0)
        .toInstant(tz).plus(1.hours).toLocalDateTime(tz)
    val end = start.toInstant(tz).plus(1.hours).toLocalDateTime(tz)
    return start to end
}

/**
 * True when an event starting at [start] in [tz] would begin before [now]. An event can't
 * start in the past — drives the Send guard + inline error. Unit-tested in
 * EventStartValidationTest.
 */
internal fun isEventStartInPast(start: LocalDateTime, tz: TimeZone, now: Instant): Boolean =
    start.toInstant(tz) < now

/**
 * Snap [start] to the current minute (in [tz]) if it would be in the past; otherwise return
 * it unchanged. Used so a past date/time pick silently becomes "now" rather than erroring.
 */
internal fun clampStartToNow(start: LocalDateTime, tz: TimeZone, now: Instant): LocalDateTime {
    if (!isEventStartInPast(start, tz, now)) return start
    val n = now.toLocalDateTime(tz)
    return LocalDateTime(n.year, n.month, n.day, n.hour, n.minute)
}

/**
 * UTC-start-of-day millis of "today" in [tz] — the earliest day the start date picker
 * allows (it greys out anything before this). The picker works in a UTC frame, so today's
 * local date is mapped to its UTC midnight. Unit-tested in EventStartValidationTest.
 */
internal fun startOfTodayUtcMillis(now: Instant, tz: TimeZone): Long =
    now.toLocalDateTime(tz).date.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()

/** Resource label for a same-day minute count: "30m", "1h", "1h 30m". */
@Composable
private fun durationMinutesLabel(totalMinutes: Long): String {
    val h = (totalMinutes / 60).toInt()
    val m = (totalMinutes % 60).toInt()
    return when {
        h > 0 && m > 0 -> stringResource(MR.string.chat_event_duration_hours_minutes, h, m)
        h > 0 -> stringResource(MR.string.chat_event_duration_hours, h)
        else -> stringResource(MR.string.chat_event_duration_minutes, m)
    }
}

/** Hyperlinked value beneath the start row: same-day → duration; spans days → "Ends <date> <time>". */
@Composable
private fun eventDurationLabel(start: LocalDateTime, end: LocalDateTime, tz: TimeZone): String {
    val parts = eventDurationParts(start, end, tz)
    return if (parts.sameDay) {
        durationMinutesLabel(parts.totalMinutes)
    } else {
        stringResource(MR.string.chat_event_ends_at, "${formatFriendlyDate(end)} ${formatTime(end)}")
    }
}

/** Preset durations offered in the picker, in minutes. */
private val DURATION_PRESETS_MIN = listOf(15L, 30L, 60L, 90L, 120L, 180L)

/**
 * Duration chooser: preset duration rows (radio single-select) plus a "Set end date and
 * time" row for the multi-day / custom case (which hands off to the existing end pickers).
 */
@Composable
private fun DurationPickerSheet(
    start: LocalDateTime,
    end: LocalDateTime,
    tz: TimeZone,
    onPickDuration: (minutes: Long) -> Unit,
    onPickCustomEnd: () -> Unit,
    onDismiss: () -> Unit,
) {
    val current = eventDurationParts(start, end, tz)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(MR.string.chat_event_duration_title)) },
        text = {
            Column {
                DURATION_PRESETS_MIN.forEach { min ->
                    val selected = current.sameDay && current.totalMinutes == min
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = { onPickDuration(min) })
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selected, onClick = { onPickDuration(min) })
                        Spacer(Modifier.width(8.dp))
                        Text(text = durationMinutesLabel(min), style = MaterialTheme.typography.bodyLarge)
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onPickCustomEnd)
                        .padding(vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(MR.string.chat_event_duration_custom_end),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(MR.string.cancel)) }
        },
    )
}

/**
 * Cover-photo source chooser. A centered, scrimmed [AlertDialog] (the in-sheet
 * picker idiom this composer already uses for date/time) — visible against the
 * dark sheet and away from the button it was launched from, unlike the old
 * dark-on-dark dropdown that opened under the finger (#785).
 */
@Composable
private fun PhotoSourceDialog(
    onPick: (PhotoSource) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(MR.string.chat_event_add_photo)) },
        text = {
            Column {
                PhotoSourceRow(
                    icon = Icons.Default.PhotoLibrary,
                    label = stringResource(MR.string.vault_image_choose_gallery),
                    onClick = { onPick(PhotoSource.Gallery) },
                )
                PhotoSourceRow(
                    icon = Icons.Default.PhotoCamera,
                    label = stringResource(MR.string.vault_image_take_photo),
                    onClick = { onPick(PhotoSource.Camera) },
                )
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(MR.string.cancel)) }
        },
    )
}

@Composable
private fun PhotoSourceRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(16.dp))
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DatePickerSheet(
    initial: LocalDateTime,
    onConfirm: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
    /** Earliest selectable day as a UTC-start-of-day millis; null = no lower bound. */
    minDateMillis: Long? = null,
) {
    val initialMs = remember(initial) {
        LocalDate(initial.year, initial.month, initial.day)
            .atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()
    }
    val selectableDates = remember(minDateMillis) {
        object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                minDateMillis == null || utcTimeMillis >= minDateMillis
            override fun isSelectableYear(year: Int): Boolean =
                minDateMillis == null ||
                    year >= Instant.fromEpochMilliseconds(minDateMillis)
                        .toLocalDateTime(TimeZone.UTC).year
        }
    }
    val state = rememberDatePickerState(
        initialSelectedDateMillis = initialMs,
        selectableDates = selectableDates,
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    val ms = state.selectedDateMillis ?: initialMs
                    val ldt = Instant.fromEpochMilliseconds(ms).toLocalDateTime(TimeZone.UTC)
                    onConfirm(LocalDate(ldt.year, ldt.month, ldt.day))
                }
            ) { Text(stringResource(MR.string.ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(MR.string.cancel)) }
        },
    ) {
        DatePicker(state = state)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickerSheet(
    initial: LocalTime,
    onConfirm: (LocalTime) -> Unit,
    onDismiss: () -> Unit,
) {
    val state = rememberTimePickerState(
        initialHour = initial.hour,
        initialMinute = initial.minute,
        is24Hour = true,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onConfirm(LocalTime(state.hour, state.minute)) }) {
                Text(stringResource(MR.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(MR.string.cancel)) }
        },
        text = { TimePicker(state = state) },
    )
}
