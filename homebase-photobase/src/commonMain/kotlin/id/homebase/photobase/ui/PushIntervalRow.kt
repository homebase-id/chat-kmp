package id.homebase.photobase.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import id.homebase.core.widget.SettingsRow
import id.homebase.core.widget.SettingsRowAction
import id.homebase.resources.MR
import id.homebase.resources.photobase_backup_dialog_cancel
import id.homebase.resources.photobase_backup_interval
import id.homebase.resources.photobase_backup_interval_desc
import id.homebase.resources.photobase_backup_interval_hours
import id.homebase.resources.photobase_backup_interval_load_failed
import id.homebase.resources.photobase_backup_interval_minutes
import id.homebase.resources.photobase_backup_interval_save_failed
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

private val PUSH_INTERVAL_CHOICES = listOf(30.minutes, 1.hours, 2.hours, 4.hours, 8.hours, 24.hours)

@Composable
fun PushIntervalRow(uiState: PhotobaseSettingsUiState, onAction: (PhotobaseSettingsUiAction) -> Unit) {
    var showDialog by remember { mutableStateOf(false) }
    SettingsRow(
        modifier = Modifier.testTag("photobasePushIntervalRow"),
        icon = Icons.Outlined.Schedule,
        title = stringResource(MR.string.photobase_backup_interval),
        supportingText = when (uiState.error) {
            PushIntervalError.Load -> stringResource(MR.string.photobase_backup_interval_load_failed)
            PushIntervalError.Save -> stringResource(MR.string.photobase_backup_interval_save_failed)
            null -> stringResource(MR.string.photobase_backup_interval_desc, intervalLabel(uiState.pushIntervalMs))
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        action = SettingsRowAction.Navigate {
            if (uiState.error == PushIntervalError.Load) onAction(PhotobaseSettingsUiAction.RetryLoad) else showDialog = true
        },
    )
    if (showDialog) {
        IntervalDialog(
            current = uiState.pushIntervalMs,
            onDismiss = { showDialog = false },
            onChoose = {
                onAction(PhotobaseSettingsUiAction.SetPushInterval(it))
                showDialog = false
            },
        )
    }
}

@Composable
private fun intervalLabel(intervalMs: Long): String {
    val minutes = intervalMs / 60_000
    return if (minutes < 60) {
        pluralStringResource(MR.plurals.photobase_backup_interval_minutes, minutes.toInt(), minutes.toInt())
    } else {
        pluralStringResource(MR.plurals.photobase_backup_interval_hours, (minutes / 60).toInt(), (minutes / 60).toInt())
    }
}

@Composable
private fun IntervalDialog(current: Long, onDismiss: () -> Unit, onChoose: (Long) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(MR.string.photobase_backup_interval)) },
        text = {
            Column {
                for (choice in PUSH_INTERVAL_CHOICES) {
                    val ms = choice.inWholeMilliseconds
                    ListItem(
                        modifier = Modifier.selectable(selected = ms == current, role = Role.RadioButton) { onChoose(ms) },
                        headlineContent = { Text(intervalLabel(ms)) },
                        leadingContent = { RadioButton(selected = ms == current, onClick = null) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(MR.string.photobase_backup_dialog_cancel)) }
        },
    )
}
