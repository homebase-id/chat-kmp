package id.homebase.chat.groodle

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import id.homebase.api.util.truncateToCodePoints
import id.homebase.chat.event.TimezonePickerSheet
import id.homebase.chat.event.friendlyZoneLabel
import id.homebase.chat.services.ChatMessageSenderService
import id.homebase.chat.services.content.MessageContent
import id.homebase.core.ui.theme.HomebaseTheme
import id.homebase.resources.MR
import id.homebase.resources.cancel
import id.homebase.resources.menu_back
import id.homebase.resources.ok
import id.homebase.resources.chat_groodle_add_time
import id.homebase.resources.chat_groodle_allow_maybe
import id.homebase.resources.chat_groodle_composer_title
import id.homebase.resources.chat_groodle_deadline
import id.homebase.resources.chat_groodle_deadline_24h
import id.homebase.resources.chat_groodle_deadline_48h
import id.homebase.resources.chat_groodle_deadline_none
import id.homebase.resources.chat_groodle_deadline_week
import id.homebase.resources.chat_groodle_duration
import id.homebase.resources.chat_groodle_duration_minutes
import id.homebase.resources.chat_groodle_err_duplicate
import id.homebase.resources.chat_groodle_err_times_missing
import id.homebase.resources.chat_groodle_field_description
import id.homebase.resources.chat_groodle_field_timezone
import id.homebase.resources.chat_groodle_field_title
import id.homebase.resources.chat_groodle_max_options
import id.homebase.resources.chat_groodle_remove_option
import id.homebase.resources.chat_groodle_send
import id.homebase.resources.chat_groodle_set_time
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
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

private const val MAX_TITLE_CODEPOINTS = GroodleDescriptor.MAX_TITLE_CODEPOINTS
private const val MAX_DESCRIPTION_CODEPOINTS = GroodleDescriptor.MAX_DESCRIPTION_CODEPOINTS
private const val MAX_SLOTS = GroodleDescriptor.MAX_SLOTS
private const val DEFAULT_DURATION_MINUTES = 60
private const val DURATION_STEP_MINUTES = 15
private const val MIN_DURATION_MINUTES = 15

/** One editable candidate slot. [id] is a stable local key for list rendering. */
private data class SlotDraft(
    val id: Long,
    val date: LocalDate,
    val startTime: LocalTime?,
    val durationMinutes: Int,
)

private enum class DeadlinePreset { NONE, H24, H48, WEEK }

/**
 * Fullscreen composer for a Groodle. State is local `remember` — the composer is
 * short-lived (open → fill → send → dismiss). Mirrors `event/EventComposerSheet`.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalUuidApi::class)
@Composable
fun GroodleComposerSheet(
    conversationId: Uuid,
    onDismiss: () -> Unit,
    onSent: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        GroodleComposerContent(
            conversationId = conversationId,
            onDismiss = onDismiss,
            onSent = onSent,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalUuidApi::class)
@Composable
private fun GroodleComposerContent(
    conversationId: Uuid,
    onDismiss: () -> Unit,
    onSent: () -> Unit,
) {
    val sender: ChatMessageSenderService = koinInject()
    val scope = rememberCoroutineScope()

    val systemTz = remember { TimeZone.currentSystemDefault() }
    val today = remember {
        Clock.System.now().toLocalDateTime(systemTz).date
    }

    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var timezone by remember { mutableStateOf(systemTz.id) }
    var allowMaybe by remember { mutableStateOf(true) }
    var deadlinePreset by remember { mutableStateOf(DeadlinePreset.H48) }

    val slots = remember { mutableStateListOf<SlotDraft>() }
    var nextId by remember { mutableStateOf(0L) }

    var sending by remember { mutableStateOf(false) }
    var showAddDate by remember { mutableStateOf(false) }
    var timePickerForId by remember { mutableStateOf<Long?>(null) }
    var tzExpanded by remember { mutableStateOf(false) }

    val allTimesSet by remember { derivedStateOf { slots.all { it.startTime != null } } }
    val hasDuplicate by remember {
        derivedStateOf {
            val keys = slots.mapNotNull { s -> s.startTime?.let { Triple(s.date, it, s.durationMinutes) } }
            keys.size != keys.toSet().size
        }
    }
    val isValid by remember {
        derivedStateOf {
            title.isNotBlank() && slots.isNotEmpty() && allTimesSet && !hasDuplicate && !sending
        }
    }

    val doSend: () -> Unit = {
        if (isValid) {
            sending = true
            scope.launch {
                val tz = runCatching { TimeZone.of(timezone) }.getOrDefault(systemTz)
                val wireSlots = slots.mapNotNull { draft ->
                    val time = draft.startTime ?: return@mapNotNull null
                    val startLdt = LocalDateTime(draft.date.year, draft.date.month, draft.date.day, time.hour, time.minute)
                    val startMs = startLdt.toInstant(tz).toEpochMilliseconds()
                    GroodleSlot(startUtcMs = startMs, endUtcMs = startMs + draft.durationMinutes * 60_000L)
                }.sortedBy { it.startUtcMs }

                val deadlineUtcMs = computeDeadline(deadlinePreset, wireSlots.firstOrNull()?.startUtcMs)

                val descriptor = GroodleDescriptor(
                    title = title.truncateToCodePoints(MAX_TITLE_CODEPOINTS),
                    description = description.truncateToCodePoints(MAX_DESCRIPTION_CODEPOINTS),
                    timezone = tz.id,
                    allowMaybe = allowMaybe,
                    deadlineUtcMs = deadlineUtcMs,
                    slots = wireSlots,
                )
                runCatching {
                    sender.sendNewTypedMessage(
                        messageUniqueId = Uuid.random(),
                        conversationId = conversationId,
                        content = MessageContent.Groodle(descriptor),
                        previousMessageUniqueId = null,
                    )
                }
                sending = false
                onSent()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(MR.string.chat_groodle_composer_title)) },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(MR.string.menu_back),
                        )
                    }
                },
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
                label = { Text(stringResource(MR.string.chat_groodle_field_title)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text(stringResource(MR.string.chat_groodle_field_description)) },
                modifier = Modifier.fillMaxWidth().height(96.dp),
            )

            // Timezone (reused from Event).
            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = friendlyZoneLabel(timezone),
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(MR.string.chat_groodle_field_timezone)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Box(
                    modifier = Modifier.matchParentSize().clickable(onClick = { tzExpanded = true }),
                )
            }

            // Allow-Maybe toggle.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(MR.string.chat_groodle_allow_maybe),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = allowMaybe, onCheckedChange = { allowMaybe = it })
            }

            // Deadline presets.
            Text(
                text = stringResource(MR.string.chat_groodle_deadline),
                style = MaterialTheme.typography.labelLarge,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DeadlineChip(stringResource(MR.string.chat_groodle_deadline_24h), deadlinePreset == DeadlinePreset.H24) { deadlinePreset = DeadlinePreset.H24 }
                DeadlineChip(stringResource(MR.string.chat_groodle_deadline_48h), deadlinePreset == DeadlinePreset.H48) { deadlinePreset = DeadlinePreset.H48 }
                DeadlineChip(stringResource(MR.string.chat_groodle_deadline_week), deadlinePreset == DeadlinePreset.WEEK) { deadlinePreset = DeadlinePreset.WEEK }
                DeadlineChip(stringResource(MR.string.chat_groodle_deadline_none), deadlinePreset == DeadlinePreset.NONE) { deadlinePreset = DeadlinePreset.NONE }
            }

            // Slot rows.
            slots.forEach { draft ->
                SlotDraftRow(
                    draft = draft,
                    onPickTime = { timePickerForId = draft.id },
                    onDurationChange = { delta ->
                        val idx = slots.indexOfFirst { it.id == draft.id }
                        if (idx >= 0) {
                            val next = (slots[idx].durationMinutes + delta).coerceAtLeast(MIN_DURATION_MINUTES)
                            slots[idx] = slots[idx].copy(durationMinutes = next)
                        }
                    },
                    onRemove = { slots.removeAll { it.id == draft.id } },
                )
            }

            OutlinedButton(
                onClick = { showAddDate = true },
                enabled = slots.size < MAX_SLOTS,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(imageVector = Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(MR.string.chat_groodle_add_time))
            }
            Text(
                text = stringResource(MR.string.chat_groodle_max_options, MAX_SLOTS),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Validation hints.
            if (slots.isNotEmpty() && !allTimesSet) {
                Text(
                    text = stringResource(MR.string.chat_groodle_err_times_missing),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (hasDuplicate) {
                Text(
                    text = stringResource(MR.string.chat_groodle_err_duplicate),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Spacer(Modifier.height(8.dp))

            Button(
                onClick = doSend,
                enabled = isValid,
                colors = ButtonDefaults.buttonColors(
                    containerColor = HomebaseTheme.extendedColors.bubbleSentSurface,
                    contentColor = HomebaseTheme.extendedColors.bubbleSentOnSurface,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(imageVector = Icons.AutoMirrored.Filled.Send, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(MR.string.chat_groodle_send))
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    if (showAddDate) {
        DatePickerSheet(
            initialDate = today,
            onConfirm = { date ->
                if (slots.size < MAX_SLOTS) {
                    val inheritedDuration = slots.lastOrNull()?.durationMinutes ?: DEFAULT_DURATION_MINUTES
                    slots.add(SlotDraft(id = nextId, date = date, startTime = null, durationMinutes = inheritedDuration))
                    nextId += 1
                }
                showAddDate = false
            },
            onDismiss = { showAddDate = false },
        )
    }

    timePickerForId?.let { id ->
        val idx = slots.indexOfFirst { it.id == id }
        if (idx >= 0) {
            TimePickerSheet(
                initial = slots[idx].startTime ?: LocalTime(9, 0),
                onConfirm = { time ->
                    val cur = slots.indexOfFirst { it.id == id }
                    if (cur >= 0) slots[cur] = slots[cur].copy(startTime = time)
                    timePickerForId = null
                },
                onDismiss = { timePickerForId = null },
            )
        } else {
            timePickerForId = null
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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeadlineChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) })
}

@Composable
private fun SlotDraftRow(
    draft: SlotDraft,
    onPickTime: () -> Unit,
    onDurationChange: (Int) -> Unit,
    onRemove: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = formatDateLabel(draft.date),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onRemove) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(MR.string.chat_groodle_remove_option),
                    )
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = onPickTime, modifier = Modifier.weight(1f)) {
                    Text(
                        text = draft.startTime?.let {
                            it.hour.toString().padStart(2, '0') + ":" + it.minute.toString().padStart(2, '0')
                        } ?: stringResource(MR.string.chat_groodle_set_time),
                    )
                }
                // Duration stepper.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { onDurationChange(-DURATION_STEP_MINUTES) }) {
                        Icon(imageVector = Icons.Default.Remove, contentDescription = stringResource(MR.string.chat_groodle_duration))
                    }
                    Text(
                        text = stringResource(MR.string.chat_groodle_duration_minutes, draft.durationMinutes),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    IconButton(onClick = { onDurationChange(DURATION_STEP_MINUTES) }) {
                        Icon(imageVector = Icons.Default.Add, contentDescription = stringResource(MR.string.chat_groodle_duration))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DatePickerSheet(
    initialDate: LocalDate,
    onConfirm: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
) {
    val initialMonthMs = remember(initialDate) {
        initialDate.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()
    }
    // Open with nothing selected and no OK button: the first tap on a day both
    // selects AND commits it (single click adds the option). Adding one by
    // mistake is cheap — each row has its own remove button. Only Cancel remains.
    val state = rememberDatePickerState(
        initialSelectedDateMillis = null,
        initialDisplayedMonthMillis = initialMonthMs,
    )
    LaunchedEffect(state.selectedDateMillis) {
        val ms = state.selectedDateMillis ?: return@LaunchedEffect
        val ldt = Instant.fromEpochMilliseconds(ms).toLocalDateTime(TimeZone.UTC)
        onConfirm(LocalDate(ldt.year, ldt.month, ldt.day))
    }
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(MR.string.cancel)) }
        },
    ) {
        DatePicker(state = state, showModeToggle = false)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickerSheet(
    initial: LocalTime,
    onConfirm: (LocalTime) -> Unit,
    onDismiss: () -> Unit,
) {
    val state = rememberTimePickerState(initialHour = initial.hour, initialMinute = initial.minute, is24Hour = true)
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

private fun formatDateLabel(date: LocalDate): String =
    "${date.dayOfWeek.name.take(3)}, ${date.month.name.take(3)} ${date.day}"

/**
 * Resolves the deadline preset to an absolute UTC instant, clamped so it never
 * lands after the earliest slot (the descriptor's `isValid` requires
 * `deadlineUtcMs <= slots.first().startUtcMs`).
 */
private fun computeDeadline(preset: DeadlinePreset, earliestSlotStartUtcMs: Long?): Long? {
    val durationMs = when (preset) {
        DeadlinePreset.NONE -> return null
        DeadlinePreset.H24 -> 24.hours.inWholeMilliseconds
        DeadlinePreset.H48 -> 48.hours.inWholeMilliseconds
        DeadlinePreset.WEEK -> 7.days.inWholeMilliseconds
    }
    val raw = Clock.System.now().toEpochMilliseconds() + durationMs
    return if (earliestSlotStartUtcMs != null) minOf(raw, earliestSlotStartUtcMs) else raw
}
