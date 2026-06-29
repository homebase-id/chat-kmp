package id.homebase.chat.groodle

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.HowToVote
import androidx.compose.material.icons.filled.LockClock
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import id.homebase.api.util.truncateToCodePoints
import id.homebase.chat.composer.ComposerEditableField
import id.homebase.chat.composer.ComposerRow
import id.homebase.chat.composer.ComposerTitleField
import id.homebase.chat.event.TimezonePickerSheet
import id.homebase.chat.event.friendlyZoneLabel
import id.homebase.chat.services.ChatMessageSenderService
import id.homebase.chat.services.content.MessageContent
import id.homebase.chat.widget.GuardedComposerSheet
import id.homebase.core.ui.theme.HomebaseTheme
import id.homebase.resources.MR
import id.homebase.resources.cancel
import id.homebase.resources.ok
import id.homebase.resources.chat_groodle_add_time
import id.homebase.resources.chat_groodle_allow_maybe
import id.homebase.resources.chat_groodle_deadline
import id.homebase.resources.chat_groodle_deadline_24h
import id.homebase.resources.chat_groodle_deadline_48h
import id.homebase.resources.chat_groodle_deadline_none
import id.homebase.resources.chat_groodle_deadline_week
import id.homebase.resources.chat_groodle_duration
import id.homebase.resources.chat_groodle_duration_decrease
import id.homebase.resources.chat_groodle_duration_increase
import id.homebase.resources.chat_groodle_duration_minutes
import id.homebase.resources.chat_groodle_err_duplicate
import id.homebase.resources.chat_groodle_err_times_missing
import id.homebase.resources.chat_groodle_field_description
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
private const val MAX_DURATION_MINUTES = 24 * 60

/** One editable candidate slot. [id] is a stable local key for list rendering. */
private data class SlotDraft(
    val id: Long,
    val date: LocalDate,
    val startTime: LocalTime?,
    val durationMinutes: Int,
)

private enum class DeadlinePreset { NONE, H24, H48, WEEK }

/**
 * Bottom-sheet composer for a Groodle (group-scheduling poll), modeled on the
 * same Material 3 quick-create language as `event/EventComposerSheet`: a
 * borderless title, then flat icon-led rows (when / time zone / allow-maybe /
 * deadline / description).
 *
 * Presentation only — [GroodleDescriptor], the wire format and the send path are
 * unchanged. State is local `remember` (the composer is short-lived:
 * open → fill → send/dismiss).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroodleComposerSheet(
    conversationId: Uuid,
    onDismiss: () -> Unit,
    onSent: () -> Unit,
) {
    GuardedComposerSheet(onDismiss = onDismiss) { sheetState, requestClose, reportUnsaved ->
        GroodleComposerContent(
            conversationId = conversationId,
            sheetState = sheetState,
            onDismiss = onDismiss,
            onSent = onSent,
            onRequestClose = requestClose,
            onUnsavedContentChange = reportUnsaved,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalUuidApi::class, ExperimentalLayoutApi::class)
@Composable
private fun GroodleComposerContent(
    conversationId: Uuid,
    sheetState: SheetState,
    onDismiss: () -> Unit,
    onSent: () -> Unit,
    onRequestClose: () -> Unit = onDismiss,
    onUnsavedContentChange: (Boolean) -> Unit = {},
) {
    val sender: ChatMessageSenderService = koinInject()
    val scope = rememberCoroutineScope()
    val brand = HomebaseTheme.extendedColors.bubbleSentSurface

    val systemTz = remember { TimeZone.currentSystemDefault() }
    val today = remember { Clock.System.now().toLocalDateTime(systemTz).date }

    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var timezone by remember { mutableStateOf(systemTz.id) }
    var allowMaybe by remember { mutableStateOf(true) }
    var deadlinePreset by remember { mutableStateOf(DeadlinePreset.H48) }

    val slots = remember { mutableStateListOf<SlotDraft>() }
    var nextId by remember { mutableStateOf(0L) }

    var sending by remember { mutableStateOf(false) }
    var showAddDate by remember { mutableStateOf(false) }
    var datePickerForId by remember { mutableStateOf<Long?>(null) }
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

    // Report unsaved content up so the sheet guards swipe/scrim/back/close
    // against accidental discard (#891).
    val hasUnsavedContent = title.isNotBlank() || description.isNotBlank() || slots.isNotEmpty()
    LaunchedEffect(hasUnsavedContent) { onUnsavedContentChange(hasUnsavedContent) }

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
            IconButton(onClick = onRequestClose) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(MR.string.cancel),
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
                Text(stringResource(MR.string.chat_groodle_send))
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
            // Title — borderless headline with a brand-blue underline indicator.
            ComposerTitleField(
                value = title,
                onValueChange = { title = it },
                placeholder = stringResource(MR.string.chat_groodle_field_title),
                brand = brand,
            )

            Spacer(Modifier.height(12.dp))

            // WHEN group — candidate time options + the add control.
            val hasSlots = slots.isNotEmpty()
            ComposerRow(
                icon = Icons.Default.Schedule,
                filled = hasSlots,
                // Empty state is a single line, so center it with the clock icon like
                // the other rows; once slots exist, top-align the icon with the list.
                verticalAlignment = if (hasSlots) Alignment.Top else Alignment.CenterVertically,
            ) {
                if (!hasSlots) {
                    Text(
                        text = stringResource(MR.string.chat_groodle_add_time),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { showAddDate = true }
                            .padding(vertical = 6.dp),
                    )
                } else {
                    Column(modifier = Modifier.weight(1f)) {
                        slots.forEachIndexed { i, draft ->
                            SlotEditLine(
                                draft = draft,
                                onPickDate = { datePickerForId = draft.id },
                                onPickTime = { timePickerForId = draft.id },
                                onDurationChange = { delta ->
                                    val idx = slots.indexOfFirst { it.id == draft.id }
                                    if (idx >= 0) {
                                        val next = (slots[idx].durationMinutes + delta).coerceIn(MIN_DURATION_MINUTES, MAX_DURATION_MINUTES)
                                        slots[idx] = slots[idx].copy(durationMinutes = next)
                                    }
                                },
                                onRemove = { slots.removeAll { it.id == draft.id } },
                            )
                            if (i < slots.lastIndex) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(vertical = 2.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                )
                            }
                        }

                        // Plain-text action (no leading icon) so the label left-aligns
                        // with the rows above, matching the Event composer.
                        TextButton(
                            onClick = { showAddDate = true },
                            enabled = slots.size < MAX_SLOTS,
                            modifier = Modifier.offset(x = (-12).dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        ) {
                            Text(stringResource(MR.string.chat_groodle_add_time))
                        }
                        Text(
                            text = stringResource(MR.string.chat_groodle_max_options, MAX_SLOTS),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        if (!allTimesSet) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = stringResource(MR.string.chat_groodle_err_times_missing),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        if (hasDuplicate) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = stringResource(MR.string.chat_groodle_err_duplicate),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }

            // Time zone (reused from Event; tappable opens the shared picker).
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

            // Allow "Maybe" — label + trailing switch.
            ComposerRow(
                icon = Icons.Default.HowToVote,
                filled = allowMaybe,
                trailing = {
                    Switch(checked = allowMaybe, onCheckedChange = { allowMaybe = it })
                },
            ) {
                Text(
                    text = stringResource(MR.string.chat_groodle_allow_maybe),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
            }

            // Voting deadline — label + preset chips.
            ComposerRow(
                icon = Icons.Default.LockClock,
                filled = deadlinePreset != DeadlinePreset.NONE,
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(MR.string.chat_groodle_deadline),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DeadlineChip(stringResource(MR.string.chat_groodle_deadline_24h), deadlinePreset == DeadlinePreset.H24) { deadlinePreset = DeadlinePreset.H24 }
                        DeadlineChip(stringResource(MR.string.chat_groodle_deadline_48h), deadlinePreset == DeadlinePreset.H48) { deadlinePreset = DeadlinePreset.H48 }
                        DeadlineChip(stringResource(MR.string.chat_groodle_deadline_week), deadlinePreset == DeadlinePreset.WEEK) { deadlinePreset = DeadlinePreset.WEEK }
                        DeadlineChip(stringResource(MR.string.chat_groodle_deadline_none), deadlinePreset == DeadlinePreset.NONE) { deadlinePreset = DeadlinePreset.NONE }
                    }
                }
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
                    placeholder = stringResource(MR.string.chat_groodle_field_description),
                    singleLine = false,
                    cursorColor = brand,
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    if (showAddDate) {
        DatePickerSheet(
            initialDate = today,
            onConfirm = { date ->
                showAddDate = false
                if (slots.size < MAX_SLOTS) {
                    val inheritedDuration = slots.lastOrNull()?.durationMinutes ?: DEFAULT_DURATION_MINUTES
                    val newId = nextId
                    slots.add(SlotDraft(id = newId, date = date, startTime = null, durationMinutes = inheritedDuration))
                    nextId += 1
                    // Time is mandatory, so chain straight into the time picker for
                    // the new option (mirrors the Event composer's date→time chain) —
                    // no separate "Set time" tap, no date-without-time slot (#890).
                    timePickerForId = newId
                }
            },
            onDismiss = { showAddDate = false },
        )
    }

    datePickerForId?.let { id ->
        val idx = slots.indexOfFirst { it.id == id }
        if (idx >= 0) {
            DatePickerSheet(
                initialDate = slots[idx].date,
                onConfirm = { date ->
                    val cur = slots.indexOfFirst { it.id == id }
                    if (cur >= 0) slots[cur] = slots[cur].copy(date = date)
                    datePickerForId = null
                    // Chain to the time picker after a date change too, so a slot
                    // can't be left with a date but no time (#890). Opens pre-filled
                    // with any existing time.
                    timePickerForId = id
                },
                onDismiss = { datePickerForId = null },
            )
        } else {
            datePickerForId = null
        }
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

/**
 * One candidate slot inside the WHEN group: a "date · time" line (the time is
 * independently tappable; date is fixed at creation), a compact duration stepper,
 * and a remove action.
 */
@Composable
private fun SlotEditLine(
    draft: SlotDraft,
    onPickDate: () -> Unit,
    onPickTime: () -> Unit,
    onDurationChange: (Int) -> Unit,
    onRemove: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = formatSlotDate(draft.date),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onPickDate)
                    .padding(vertical = 6.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = draft.startTime?.let { formatTime(it) } ?: stringResource(MR.string.chat_groodle_set_time),
                style = MaterialTheme.typography.bodyLarge,
                color = if (draft.startTime != null) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onPickTime)
                    .padding(vertical = 6.dp, horizontal = 6.dp),
            )
            Spacer(Modifier.weight(1f))
            // Default IconButton sizing keeps the 48dp minimum touch target.
            IconButton(onClick = onRemove) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(MR.string.chat_groodle_remove_option),
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // Duration stepper — distinct labels per button so a screen reader can
        // tell increment from decrement.
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onDurationChange(-DURATION_STEP_MINUTES) }) {
                Icon(
                    imageVector = Icons.Default.Remove,
                    contentDescription = stringResource(MR.string.chat_groodle_duration_decrease),
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = stringResource(MR.string.chat_groodle_duration_minutes, draft.durationMinutes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            IconButton(onClick = { onDurationChange(DURATION_STEP_MINUTES) }) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = stringResource(MR.string.chat_groodle_duration_increase),
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeadlineChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) })
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

/** e.g. "Tue, Jun 16". */
private fun formatSlotDate(date: LocalDate): String {
    val dow = date.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }.take(3)
    val mon = date.month.name.lowercase().replaceFirstChar { it.uppercase() }.take(3)
    return "$dow, $mon ${date.day}"
}

/** 24h "HH:mm". */
private fun formatTime(t: LocalTime): String =
    t.hour.toString().padStart(2, '0') + ":" + t.minute.toString().padStart(2, '0')

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
