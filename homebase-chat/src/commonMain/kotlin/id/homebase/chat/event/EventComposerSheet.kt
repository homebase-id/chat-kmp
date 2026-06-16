package id.homebase.chat.event

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import id.homebase.api.client.location.LocationPreviewProvider
import id.homebase.api.util.truncateToCodePoints
import id.homebase.chat.location.LocationResult
import id.homebase.chat.location.rememberCurrentLocationLauncher
import id.homebase.chat.services.ChatMessageSenderService
import id.homebase.chat.services.content.MessageContent
import id.homebase.core.ui.theme.HomebaseTheme
import id.homebase.core.util.rememberImeOffsetState
import id.homebase.resources.MR
import id.homebase.resources.cancel
import id.homebase.resources.close
import id.homebase.resources.ok
import id.homebase.resources.chat_event_add_end_time
import id.homebase.resources.chat_event_description_hint
import id.homebase.resources.chat_event_location_hint
import id.homebase.resources.chat_event_meeting_url_hint
import id.homebase.resources.chat_event_remove_end_time
import id.homebase.resources.chat_event_send
import id.homebase.resources.chat_event_title_hint
import id.homebase.resources.chat_event_use_current_location
import kotlin.time.Clock
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

/** Vertical gap reserved for the icon column so unlabeled sub-rows align under the row text. */
private val RowIconGap = 20.dp

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

    // Keyboard inset — matches Vault/Chat. Pad by the PURE ime height (ime minus
    // the home-indicator inset that WindowInsets.ime double-counts on iOS) so the
    // last field sits flush above the keyboard. Raw imePadding() leaves a
    // home-indicator-sized gray gap on iOS (see ImeOffsetUtils).
    val imeState = rememberImeOffsetState()
    val pureImeBottomDp = with(imeState.density) { imeState.pureImeBottomPx.toDp() }

    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    val systemTz = remember { TimeZone.currentSystemDefault() }
    var timezone by remember { mutableStateOf(systemTz.id) }

    // Default start: next round hour. End: start + 1 hour.
    val defaultStart = remember {
        val now = Clock.System.now().toLocalDateTime(systemTz)
        LocalDateTime(
            year = now.year, month = now.month, day = now.day,
            hour = (now.hour + 1).coerceAtMost(23), minute = 0, second = 0, nanosecond = 0,
        )
    }
    var startDateTime by remember { mutableStateOf(defaultStart) }
    var endDateTime by remember {
        mutableStateOf(
            LocalDateTime(
                year = defaultStart.year, month = defaultStart.month, day = defaultStart.day,
                hour = (defaultStart.hour + 1).coerceAtMost(23), minute = 0,
            )
        )
    }
    var hasEndTime by remember { mutableStateOf(false) }
    var locationText by remember { mutableStateOf("") }
    var locationLat by remember { mutableStateOf<Double?>(null) }
    var locationLon by remember { mutableStateOf<Double?>(null) }
    var meetingUrl by remember { mutableStateOf("") }
    var fetchingLocation by remember { mutableStateOf(false) }

    val locationPreviewProvider: LocationPreviewProvider = koinInject()
    val currentLocationLauncher = rememberCurrentLocationLauncher { result ->
        when (result) {
            is LocationResult.Success -> {
                scope.launch {
                    val preview = runCatching {
                        locationPreviewProvider.getLocationPreview(
                            result.fix.latitude, result.fix.longitude,
                        )
                    }.getOrNull()
                    locationText = preview?.address?.takeIf { it.isNotBlank() }
                        ?: "${result.fix.latitude}, ${result.fix.longitude}"
                    locationLat = result.fix.latitude
                    locationLon = result.fix.longitude
                    fetchingLocation = false
                }
            }
            is LocationResult.PermissionDenied,
            is LocationResult.Unavailable -> {
                fetchingLocation = false
            }
        }
    }

    var sending by remember { mutableStateOf(false) }
    var showStartDate by remember { mutableStateOf(false) }
    var showStartTime by remember { mutableStateOf(false) }
    var showEndDate by remember { mutableStateOf(false) }
    var showEndTime by remember { mutableStateOf(false) }
    var tzExpanded by remember { mutableStateOf(false) }

    val isValid by remember {
        derivedStateOf { title.isNotBlank() && !sending }
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
                val startUtcMs = startDateTime.toInstant(tz).toEpochMilliseconds()
                val endUtcMs = if (hasEndTime) endDateTime.toInstant(tz).toEpochMilliseconds() else null
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
                runCatching {
                    sender.sendNewTypedMessage(
                        messageUniqueId = Uuid.random(),
                        conversationId = conversationId,
                        content = MessageContent.Event(descriptor),
                        previousMessageUniqueId = null,
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
                // Pure-IME bottom inset OUTSIDE verticalScroll shrinks the scroll
                // viewport by exactly the keyboard height; the focused-field
                // auto-scroll then parks the last row just above the keyboard
                // (matches Vault/Chat — no gray home-indicator gap on iOS).
                .padding(bottom = pureImeBottomDp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            // Title — borderless headline with a brand-blue underline indicator.
            val titleInteraction = remember { MutableInteractionSource() }
            val titleFocused by titleInteraction.collectIsFocusedAsState()
            BasicTextField(
                value = title,
                onValueChange = { title = it },
                singleLine = true,
                textStyle = MaterialTheme.typography.headlineSmall.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(brand),
                interactionSource = titleInteraction,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 6.dp),
                decorationBox = { inner ->
                    Box {
                        if (title.isEmpty()) {
                            Text(
                                text = stringResource(MR.string.chat_event_title_hint),
                                style = MaterialTheme.typography.headlineSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        inner()
                    }
                },
            )
            HorizontalDivider(
                thickness = if (titleFocused) 2.dp else 1.dp,
                color = if (titleFocused) brand else MaterialTheme.colorScheme.outlineVariant,
            )

            Spacer(Modifier.height(12.dp))

            // WHEN group — clock row (start/end + toggle) and timezone row.
            ComposerRow(icon = Icons.Default.Schedule, filled = true, verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    DateTimeLine(
                        local = startDateTime,
                        onPickDate = { showStartDate = true },
                        onPickTime = { showStartTime = true },
                    )
                    if (hasEndTime) {
                        DateTimeLine(
                            local = endDateTime,
                            onPickDate = { showEndDate = true },
                            onPickTime = { showEndTime = true },
                        )
                    }
                    TextButton(
                        onClick = { hasEndTime = !hasEndTime },
                        // Negative offset cancels the wider horizontal content
                        // padding, so the label stays left-aligned with the date
                        // above it while the hover/ripple splash reads wider.
                        modifier = Modifier.offset(x = (-12).dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Text(
                            text = stringResource(
                                if (hasEndTime) MR.string.chat_event_remove_end_time
                                else MR.string.chat_event_add_end_time,
                            ),
                        )
                    }
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
                EditableField(
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
                EditableField(
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
                EditableField(
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
            onConfirm = { date ->
                startDateTime = LocalDateTime(date.year, date.month, date.day, startDateTime.hour, startDateTime.minute)
                showStartDate = false
            },
            onDismiss = { showStartDate = false },
        )
    }
    if (showStartTime) {
        TimePickerSheet(
            initial = LocalTime(startDateTime.hour, startDateTime.minute),
            onConfirm = { time ->
                startDateTime = LocalDateTime(startDateTime.year, startDateTime.month, startDateTime.day, time.hour, time.minute)
                showStartTime = false
            },
            onDismiss = { showStartTime = false },
        )
    }
    if (showEndDate) {
        DatePickerSheet(
            initial = endDateTime,
            onConfirm = { date ->
                endDateTime = LocalDateTime(date.year, date.month, date.day, endDateTime.hour, endDateTime.minute)
                showEndDate = false
            },
            onDismiss = { showEndDate = false },
        )
    }
    if (showEndTime) {
        TimePickerSheet(
            initial = LocalTime(endDateTime.hour, endDateTime.minute),
            onConfirm = { time ->
                endDateTime = LocalDateTime(endDateTime.year, endDateTime.month, endDateTime.day, time.hour, time.minute)
                showEndTime = false
            },
            onDismiss = { showEndTime = false },
        )
    }
}

/** A flat, icon-led row: leading icon + [content] + optional [trailing]. */
@Composable
private fun ComposerRow(
    icon: ImageVector,
    filled: Boolean,
    modifier: Modifier = Modifier,
    verticalAlignment: Alignment.Vertical = Alignment.CenterVertically,
    trailing: @Composable (() -> Unit)? = null,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = verticalAlignment,
        horizontalArrangement = Arrangement.spacedBy(RowIconGap),
    ) {
        Icon(
            imageVector = icon,
            // Decorative — the adjacent text/field conveys the row's meaning.
            contentDescription = null,
            tint = if (filled) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        content()
        trailing?.invoke()
    }
}

/** Inline, borderless editable text used inside a [ComposerRow] (no box chrome). */
@Composable
private fun RowScope.EditableField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    singleLine: Boolean,
    cursorColor: androidx.compose.ui.graphics.Color,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = singleLine,
        textStyle = MaterialTheme.typography.bodyLarge.copy(
            color = MaterialTheme.colorScheme.onSurface,
        ),
        cursorBrush = SolidColor(cursorColor),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = Modifier.weight(1f),
        decorationBox = { inner ->
            Box {
                if (value.isEmpty()) {
                    Text(
                        text = placeholder,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                inner()
            }
        },
    )
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DatePickerSheet(
    initial: LocalDateTime,
    onConfirm: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
) {
    val initialMs = remember(initial) {
        LocalDate(initial.year, initial.month, initial.day)
            .atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()
    }
    val state = rememberDatePickerState(initialSelectedDateMillis = initialMs)
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
