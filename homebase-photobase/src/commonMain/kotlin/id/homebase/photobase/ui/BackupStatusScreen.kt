package id.homebase.photobase.ui

import id.homebase.resources.photobase_status_paused_background_refresh
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.common.util.formatBytes
import id.homebase.photobase.backup.BackupIndicator
import id.homebase.photobase.backup.BackupPauseReason
import id.homebase.photobase.backup.backupIndicatorOf
import id.homebase.photobase.backup.BackupStatus
import id.homebase.core.widget.SettingsTopBar
import id.homebase.core.util.formatTimestamp
import id.homebase.resources.MR
import id.homebase.resources.photobase_status_backed_up
import id.homebase.resources.photobase_status_failures
import id.homebase.resources.photobase_status_last_success
import id.homebase.resources.photobase_status_never
import id.homebase.resources.photobase_status_paused_auth
import id.homebase.resources.photobase_status_paused_battery
import id.homebase.resources.photobase_status_paused_charging
import id.homebase.resources.photobase_status_paused_library
import id.homebase.resources.photobase_status_paused_low_power
import id.homebase.resources.photobase_status_paused_network
import id.homebase.resources.photobase_status_paused_off
import id.homebase.resources.photobase_status_paused_storage
import id.homebase.resources.photobase_status_paused_wifi
import id.homebase.resources.photobase_status_pending
import id.homebase.resources.photobase_status_progress
import id.homebase.resources.photobase_status_size_backed_up
import id.homebase.resources.photobase_status_size_left
import id.homebase.resources.photobase_status_retry
import id.homebase.resources.photobase_status_running
import id.homebase.resources.photobase_status_title
import id.homebase.resources.photobase_status_all_backed_up
import id.homebase.resources.photobase_status_up_to_date
import id.homebase.resources.photobase_status_uploading
import kotlin.time.Instant
import org.jetbrains.compose.resources.stringResource

@Composable
fun BackupStatusScreen(viewModel: BackupSettingsViewModel, onBack: () -> Unit) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            SettingsTopBar(title = stringResource(MR.string.photobase_status_title), onBack = onBack)
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .consumeWindowInsets(innerPadding)
                .padding(innerPadding)
                .verticalScroll(rememberScrollState()),
        ) {
            BackupStatusContent(uiState.status, uiState.settings.enabled) {
                viewModel.onAction(BackupSettingsUiAction.RetryFailures)
            }
        }
    }
}

@Composable
fun BackupStatusContent(status: BackupStatus, backupEnabled: Boolean, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        StatusHero(status, backupEnabled)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile(status.backedUpCount, stringResource(MR.string.photobase_status_backed_up), Modifier.weight(1f))
            StatTile(status.pendingCount, stringResource(MR.string.photobase_status_pending), Modifier.weight(1f))
            StatTile(status.uploadingCount, stringResource(MR.string.photobase_status_uploading), Modifier.weight(1f))
        }
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                DetailRow(stringResource(MR.string.photobase_status_size_backed_up), formatBytes(status.backedUpBytes))
                DetailRow(stringResource(MR.string.photobase_status_size_left), formatBytes(status.remainingBytes))
                DetailRow(
                    stringResource(MR.string.photobase_status_last_success),
                    status.lastSuccessMs?.let { formatTimestamp(Instant.fromEpochMilliseconds(it)) }
                        ?: stringResource(MR.string.photobase_status_never),
                )
            }
        }
        if (status.failedCount > 0) FailureSection(status, onRetry)
    }
}

@Composable
private fun StatusHero(status: BackupStatus, backupEnabled: Boolean) {
    val indicator = backupIndicatorOf(status, backupEnabled)
    val color = backupIndicatorColor(indicator)
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().testTag("backupStatusHero"),
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                // The headline beside it says the same thing.
                Icon(backupIndicatorIcon(indicator), contentDescription = null, tint = color, modifier = Modifier.size(40.dp))
                Text(backupStatusSummary(status), style = MaterialTheme.typography.titleLarge)
            }
            if (status.pendingCount > 0 || status.uploadingCount > 0) {
                LinearProgressIndicator(
                    progress = { status.progress },
                    color = color,
                    modifier = Modifier.fillMaxWidth().testTag("backupStatusProgress"),
                )
                Text(
                    stringResource(MR.string.photobase_status_progress, status.backedUpCount, status.totalCount),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StatTile(value: Long, label: String, modifier: Modifier) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.large, modifier = modifier) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(value.toString(), style = MaterialTheme.typography.headlineSmall)
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun FailureSection(status: BackupStatus, onRetry: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().testTag("backupStatusFailures"),
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 16.dp, end = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    backupIndicatorIcon(BackupIndicator.NeedsAttention),
                    contentDescription = null,
                    tint = backupIndicatorColor(BackupIndicator.NeedsAttention),
                )
                Text(stringResource(MR.string.photobase_status_failures), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(status.failedCount.toString(), style = MaterialTheme.typography.titleMedium)
            }
            for (failure in status.failures) {
                ListItem(headlineContent = { Text(failure.reason) }, supportingContent = { Text(failure.deviceAssetId) })
            }
            Button(onClick = onRetry, modifier = Modifier.fillMaxWidth().padding(16.dp).testTag("backupStatusRetry")) {
                Text(stringResource(MR.string.photobase_status_retry))
            }
        }
    }
}

@Composable
internal fun backupStatusSummary(status: BackupStatus): String = when {
    status.isRunning -> stringResource(MR.string.photobase_status_running)
    status.pauseReason != null -> stringResource(
        when (status.pauseReason) {
            BackupPauseReason.BackupOff -> MR.string.photobase_status_paused_off
            BackupPauseReason.NoLibraryAccess -> MR.string.photobase_status_paused_library
            BackupPauseReason.SignedOut -> MR.string.photobase_status_paused_auth
            BackupPauseReason.NoNetwork -> MR.string.photobase_status_paused_network
            BackupPauseReason.WaitingForWifi -> MR.string.photobase_status_paused_wifi
            BackupPauseReason.WaitingForCharging -> MR.string.photobase_status_paused_charging
            BackupPauseReason.LowBattery -> MR.string.photobase_status_paused_battery
            BackupPauseReason.LowPowerMode -> MR.string.photobase_status_paused_low_power
            BackupPauseReason.StorageFull -> MR.string.photobase_status_paused_storage
        },
    )
    status.backgroundRefreshOff -> stringResource(MR.string.photobase_status_paused_background_refresh)
    status.isAllBackedUp -> stringResource(MR.string.photobase_status_all_backed_up)
    else -> stringResource(MR.string.photobase_status_up_to_date)
}
