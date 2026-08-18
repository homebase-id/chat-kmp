package id.homebase.core.ui.screens.location

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import id.homebase.chat.data.ContactUiModel
import id.homebase.core.location.emergency.locateWindowOptionsHours
import id.homebase.core.ui.screens.contactbook.components.AdaptiveSheet
import id.homebase.resources.MR
import id.homebase.resources.cancel
import id.homebase.resources.live_location_age_minutes
import id.homebase.resources.location_locate_age_days
import id.homebase.resources.location_locate_age_hours
import id.homebase.resources.location_locate_ambush_explainer
import id.homebase.resources.location_locate_ambush_label
import id.homebase.resources.location_locate_confirm
import id.homebase.resources.location_locate_last_point
import id.homebase.resources.location_locate_last_point_none
import id.homebase.resources.location_locate_panel_explainer
import id.homebase.resources.location_locate_panel_title
import id.homebase.resources.location_locate_reason_hint
import id.homebase.resources.location_locate_window_days
import id.homebase.resources.location_locate_window_hours
import id.homebase.resources.location_locate_window_label
import org.jetbrains.compose.resources.stringResource

/**
 * The emergency locate request panel (slide-up on phones, dialog on wide layouts):
 * explainer with the contact's name, required free-text justification, how-far-back
 * selection floored at the peer's last-data-point age + 1h (capped 4 days), and the
 * Ambush toggle (delay the notice to the person by 24h — receiver-side embargo).
 *
 * Pure input collector: validation is local (non-blank explanation), the actual
 * send+fetch runs in LocationViewModel via [onConfirm].
 */
@Composable
fun EmergencyLocateSheet(
    contact: ContactUiModel,
    lastPointAgeMs: Long?,
    submitting: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (explanation: String, windowHours: Int, ambush: Boolean) -> Unit,
) {
    val options = remember(lastPointAgeMs) { locateWindowOptionsHours(lastPointAgeMs) }
    var explanation by remember { mutableStateOf("") }
    var windowHours by remember(options) { mutableStateOf(options.first()) }
    var ambush by remember { mutableStateOf(false) }
    var windowMenuOpen by remember { mutableStateOf(false) }

    AdaptiveSheet(onDismiss = onDismiss, dismissible = !submitting) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(MR.string.location_locate_panel_title),
                style = MaterialTheme.typography.titleLarge,
            )

            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(MR.string.location_locate_panel_explainer, contact.name),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp),
                )
            }

            OutlinedTextField(
                value = explanation,
                onValueChange = { explanation = it },
                placeholder = { Text(stringResource(MR.string.location_locate_reason_hint)) },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
                enabled = !submitting,
                shape = MaterialTheme.shapes.large,
            )

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = lastPointAgeMs?.let {
                        stringResource(MR.string.location_locate_last_point, locateAgeLabel(it))
                    } ?: stringResource(MR.string.location_locate_last_point_none),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Box {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !submitting) { windowMenuOpen = true }
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = stringResource(MR.string.location_locate_window_label),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = windowOptionLabel(windowHours),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Icon(
                            imageVector = Icons.Default.ArrowDropDown,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    DropdownMenu(
                        expanded = windowMenuOpen,
                        onDismissRequest = { windowMenuOpen = false },
                    ) {
                        options.forEach { hours ->
                            DropdownMenuItem(
                                text = { Text(windowOptionLabel(hours)) },
                                onClick = {
                                    windowHours = hours
                                    windowMenuOpen = false
                                },
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(MR.string.location_locate_ambush_label),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = stringResource(MR.string.location_locate_ambush_explainer),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = ambush,
                    onCheckedChange = { ambush = it },
                    enabled = !submitting,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss, enabled = !submitting) {
                    Text(stringResource(MR.string.cancel))
                }
                Button(
                    onClick = { onConfirm(explanation.trim(), windowHours, ambush) },
                    enabled = !submitting && explanation.isNotBlank(),
                ) {
                    Text(stringResource(MR.string.location_locate_confirm))
                }
            }
        }
    }
}

/** "Last 6 hours" / "Last 2 days" — days for whole-day multiples of 24h. */
@Composable
private fun windowOptionLabel(hours: Int): String =
    if (hours >= 24 && hours % 24 == 0) {
        stringResource(MR.string.location_locate_window_days, hours / 24)
    } else {
        stringResource(MR.string.location_locate_window_hours, hours)
    }

/** Compact age label, same bucketing as the dashboard row ("42m" / "3h" / "5d"). */
@Composable
private fun locateAgeLabel(ageMs: Long): String = when (val bucket = locateAgeBucket(ageMs)) {
    is LocateAgeBucket.Minutes -> stringResource(MR.string.live_location_age_minutes, bucket.minutes)
    is LocateAgeBucket.Hours -> stringResource(MR.string.location_locate_age_hours, bucket.hours)
    is LocateAgeBucket.Days -> stringResource(MR.string.location_locate_age_days, bucket.days)
}
