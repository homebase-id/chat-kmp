package id.homebase.chat.event

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import id.homebase.api.client.auth.OwnerSessionRepository
import id.homebase.api.client.location.LocationPreviewProvider
import id.homebase.api.util.truncateToCodePoints
import id.homebase.chat.location.LocationResult
import id.homebase.chat.location.rememberCurrentLocationLauncher
import id.homebase.chat.services.ChatMessageSenderService
import id.homebase.resources.MR
import id.homebase.resources.cancel
import id.homebase.resources.chat_event_create_title
import id.homebase.resources.chat_event_field_description
import id.homebase.resources.chat_event_field_ends
import id.homebase.resources.chat_event_field_location
import id.homebase.resources.chat_event_field_meeting_url
import id.homebase.resources.chat_event_field_starts
import id.homebase.resources.chat_event_field_timezone
import id.homebase.resources.chat_event_field_title
import id.homebase.resources.chat_event_send
import id.homebase.resources.chat_event_use_current_location
import id.homebase.resources.ok
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

/**
 * Fullscreen dialog that collects event details and sends the message.
 *
 * State is local — the composer is short-lived (open → fill → send → dismiss)
 * and doesn't survive process death meaningfully, so `remember` is enough.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalUuidApi::class)
@Composable
fun EventComposerSheet(
    conversationId: Uuid,
    onDismiss: () -> Unit,
    onSent: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        EventComposerContent(
            conversationId = conversationId,
            onDismiss = onDismiss,
            onSent = onSent,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalUuidApi::class)
@Composable
private fun EventComposerContent(
    conversationId: Uuid,
    onDismiss: () -> Unit,
    onSent: () -> Unit,
) {
    val sender: ChatMessageSenderService = koinInject()
    val ownerSession: OwnerSessionRepository = koinInject()
    val scope = rememberCoroutineScope()

    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    val systemTz = remember { TimeZone.currentSystemDefault() }
    var timezone by remember { mutableStateOf(systemTz.id) }

    // Default start: next round hour. End: start + 1 hour.
    val defaultStart = remember {
        val now = Clock.System.now().toLocalDateTime(systemTz)
        val rounded = LocalDateTime(
            year = now.year, month = now.month, day = now.day,
            hour = (now.hour + 1).coerceAtMost(23), minute = 0, second = 0, nanosecond = 0,
        )
        rounded
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(MR.string.chat_event_create_title)) },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(MR.string.cancel))
                    }
                },
                actions = {
                    IconButton(
                        enabled = isValid,
                        onClick = {
                            if (!isValid) return@IconButton
                            sending = true
                            scope.launch {
                                val tz = runCatching { TimeZone.of(timezone) }.getOrDefault(systemTz)
                                val startUtcMs = startDateTime.toInstant(tz).toEpochMilliseconds()
                                val endUtcMs = if (hasEndTime) endDateTime.toInstant(tz).toEpochMilliseconds() else null
                                val authorOdinId = ownerSession.user.value?.odinId?.domainName ?: ""
                                val descriptor = EventDescriptor(
                                    eventId = Uuid.random().toString(),
                                    title = title.truncateToCodePoints(MAX_TITLE_CODEPOINTS),
                                    description = description.truncateToCodePoints(MAX_DESCRIPTION_CODEPOINTS),
                                    startUtcMs = startUtcMs,
                                    endUtcMs = endUtcMs,
                                    timezone = tz.id,
                                    locationText = locationText.takeIf { it.isNotBlank() },
                                    lat = locationLat,
                                    lon = locationLon,
                                    meetingUrl = meetingUrl.takeIf { it.isNotBlank() },
                                    createdByOdinId = authorOdinId,
                                    createdAtUtcMs = Clock.System.now().toEpochMilliseconds(),
                                )
                                runCatching {
                                    sender.sendNewEventMessage(
                                        messageUniqueId = Uuid.random(),
                                        conversationId = conversationId,
                                        descriptor = descriptor,
                                        previousMessageUniqueId = null,
                                    )
                                }
                                sending = false
                                onSent()
                            }
                        }
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(MR.string.chat_event_send))
                    }
                }
            )
        },
        modifier = Modifier.fillMaxSize(),
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text(stringResource(MR.string.chat_event_field_title)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text(stringResource(MR.string.chat_event_field_description)) },
                modifier = Modifier.fillMaxWidth().height(120.dp),
            )

            DateTimeRow(
                label = stringResource(MR.string.chat_event_field_starts),
                local = startDateTime,
                onPickDate = { showStartDate = true },
                onPickTime = { showStartTime = true },
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { hasEndTime = !hasEndTime }) {
                    Text(if (hasEndTime) "Remove end time" else "Add end time")
                }
            }
            if (hasEndTime) {
                DateTimeRow(
                    label = stringResource(MR.string.chat_event_field_ends),
                    local = endDateTime,
                    onPickDate = { showEndDate = true },
                    onPickTime = { showEndTime = true },
                )
            }

            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = timezone,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(MR.string.chat_event_field_timezone)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .clickable(onClick = { tzExpanded = true })
                )
                androidx.compose.material3.DropdownMenu(
                    expanded = tzExpanded,
                    onDismissRequest = { tzExpanded = false },
                ) {
                    val zones = remember { TimeZone.availableZoneIds.sorted() }
                    zones.forEach { id ->
                        DropdownMenuItem(
                            text = { Text(id) },
                            onClick = {
                                timezone = id
                                tzExpanded = false
                            }
                        )
                    }
                }
            }

            OutlinedTextField(
                value = locationText,
                onValueChange = {
                    locationText = it
                    // The user is hand-editing the address, so previously captured
                    // coordinates no longer correspond. Drop them.
                    locationLat = null
                    locationLon = null
                },
                label = { Text(stringResource(MR.string.chat_event_field_location)) },
                singleLine = true,
                trailingIcon = {
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
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = meetingUrl,
                onValueChange = { meetingUrl = it },
                label = { Text(stringResource(MR.string.chat_event_field_meeting_url)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(24.dp))
        }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateTimeRow(
    label: String,
    local: LocalDateTime,
    onPickDate: () -> Unit,
    onPickTime: () -> Unit,
) {
    Column {
        Text(text = label, style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onPickDate, modifier = Modifier.weight(1f)) {
                Text(local.toString().substringBefore('T'))
            }
            OutlinedButton(onClick = onPickTime, modifier = Modifier.weight(1f)) {
                Text("${local.hour.toString().padStart(2, '0')}:${local.minute.toString().padStart(2, '0')}")
            }
        }
    }
}

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
