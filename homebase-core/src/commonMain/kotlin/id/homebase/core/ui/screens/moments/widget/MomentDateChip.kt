package id.homebase.core.ui.screens.moments.widget

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import id.homebase.resources.MR
import id.homebase.resources.cancel
import id.homebase.resources.cd_moment_date_chip
import id.homebase.resources.moments_chip_pick_date
import id.homebase.resources.moments_chip_use_photo_date
import id.homebase.resources.ok
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Instant
import org.jetbrains.compose.resources.stringResource

/**
 * Persistent date chip that sits above the photo pager in the moments
 * composer. Shows the resolved capture date — auto-derived from the
 * earliest EXIF among the photos, or whatever the user picked. Tapping
 * opens a [DatePickerDialog].
 *
 * Two confirm paths from the dialog:
 *   - Pick a date → emits an absolute-millis override. The VM flips the
 *     "user override" flag so adding more photos won't overwrite the choice.
 *   - "Use photo date" → emits null. The VM clears the override and
 *     resumes auto-derivation, picking up any later-arriving EXIF.
 *
 * If the user has no photos with EXIF dates yet, the chip shows "Pick a
 * date" so the action is still discoverable.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MomentDateChip(
    momentInstant: Instant?,
    canResetToAuto: Boolean,
    onPickDate: (epochMillis: Long?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showPicker by remember { mutableStateOf(false) }

    val deviceTz = remember { TimeZone.currentSystemDefault() }
    val display = momentInstant?.toLocalDateTime(deviceTz)
    val label = display?.let(::formatMonthDayYear)
        ?: stringResource(MR.string.moments_chip_pick_date)

    val chipCd = stringResource(MR.string.cd_moment_date_chip)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AssistChip(
            onClick = { showPicker = true },
            label = { Text(label) },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.CalendarToday,
                    contentDescription = null,
                    modifier = Modifier.size(AssistChipDefaults.IconSize),
                )
            },
            modifier = Modifier.semantics { contentDescription = chipCd },
        )
    }

    if (showPicker) {
        // DatePicker thinks in UTC midnight per its public contract; convert
        // both directions through that to avoid an off-by-one when the user
        // is east/west of UTC. We seed from the device-local date so the
        // picker opens on the visible date, not yesterday.
        val initialMs = remember(display) {
            val d = display ?: Clock.System.now().toLocalDateTime(deviceTz)
            LocalDate(d.year, d.month, d.day)
                .atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()
        }
        val state = rememberDatePickerState(initialSelectedDateMillis = initialMs)
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        val ms = state.selectedDateMillis ?: initialMs
                        // DatePicker returns UTC midnight of the picked date.
                        // Translate to local-noon to avoid any tz boundary
                        // ever flipping the displayed day, then convert to
                        // device-tz instant millis for the override.
                        val ldtUtc = Instant.fromEpochMilliseconds(ms)
                            .toLocalDateTime(TimeZone.UTC)
                        // Anchor to local noon: tz boundaries can never flip
                        // the displayed day from a noon anchor.
                        val pickedLocal = LocalDateTime(
                            year = ldtUtc.year,
                            month = ldtUtc.month,
                            day = ldtUtc.day,
                            hour = 12, minute = 0, second = 0,
                        )
                        onPickDate(pickedLocal.toInstant(deviceTz).toEpochMilliseconds())
                        showPicker = false
                    }
                ) { Text(stringResource(MR.string.ok)) }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (canResetToAuto) {
                        TextButton(onClick = {
                            onPickDate(null)
                            showPicker = false
                        }) {
                            Text(stringResource(MR.string.moments_chip_use_photo_date))
                        }
                    }
                    TextButton(onClick = { showPicker = false }) {
                        Text(stringResource(MR.string.cancel))
                    }
                }
            },
        ) {
            DatePicker(state = state)
        }
    }
}

private fun formatMonthDayYear(dt: LocalDateTime): String {
    val month = dt.month.name.take(3).lowercase().replaceFirstChar { it.uppercase() }
    return "$month ${dt.day}, ${dt.year}"
}
