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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Videocam
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
import id.homebase.photobase.backup.BackupWork
import id.homebase.photobase.backup.BackupWorkStage
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
import id.homebase.resources.photobase_status_paused_phone_storage
import id.homebase.resources.photobase_status_paused_too_hot
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
import id.homebase.resources.photobase_status_percent
import id.homebase.resources.photobase_status_preparing_photo
import id.homebase.resources.photobase_status_preparing_video
import id.homebase.resources.photobase_status_uploading_photo
import id.homebase.resources.photobase_status_uploading_video
import id.homebase.resources.moments_detail_shared_with_more
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
            StatTile(status.waitingCount, stringResource(MR.string.photobase_status_pending), Modifier.weight(1f))
            StatTile(status.inProgressCount, stringResource(MR.string.photobase_status_uploading), Modifier.weight(1f))
        }
        if (status.working.isNotEmpty()) WorkSection(status.working)
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                status.backedUpBytes?.let { DetailRow(stringResource(MR.string.photobase_status_size_backed_up), formatBytes(it)) }
                status.remainingBytes?.let { DetailRow(stringResource(MR.string.photobase_status_size_left), formatBytes(it)) }
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
                Text(backupStatusSummary(status, backupEnabled), style = MaterialTheme.typography.titleLarge)
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
private fun WorkSection(working: List<BackupWork>) {
    val shown = working.take(MAX_WORK_SHOWN)
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().testTag("backupStatusWork"),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            shown.forEach { WorkRow(it) }
            if (working.size > shown.size) {
                Text(
                    stringResource(MR.string.moments_detail_shared_with_more, working.size - shown.size),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun WorkRow(work: BackupWork) {
    val percent = work.percent
    val label = when (work.stage) {
        BackupWorkStage.Preparing ->
            if (work.isVideo) MR.string.photobase_status_preparing_video else MR.string.photobase_status_preparing_photo
        BackupWorkStage.Uploading ->
            if (work.isVideo) MR.string.photobase_status_uploading_video else MR.string.photobase_status_uploading_photo
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        // The label beside it says photo or video.
        Icon(
            if (work.isVideo) Icons.Outlined.Videocam else Icons.Outlined.Image,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(label), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                if (percent != null) {
                    Text(
                        stringResource(MR.string.photobase_status_percent, percent),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (percent == null) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

private const val MAX_WORK_SHOWN = 3

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
                ListItem(supportingContent = { Text(failure.deviceAssetId) }) { Text(failure.reason) }
            }
            Button(onClick = onRetry, modifier = Modifier.fillMaxWidth().padding(16.dp).testTag("backupStatusRetry")) {
                Text(stringResource(MR.string.photobase_status_retry))
            }
        }
    }
}

@Composable
internal fun backupStatusSummary(status: BackupStatus, backupEnabled: Boolean): String = when {
    !backupEnabled -> stringResource(MR.string.photobase_status_paused_off)
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
            BackupPauseReason.PhoneStorageLow -> MR.string.photobase_status_paused_phone_storage
            BackupPauseReason.TooHot -> MR.string.photobase_status_paused_too_hot
        },
    )
    status.backgroundRefreshOff -> stringResource(MR.string.photobase_status_paused_background_refresh)
    status.working.isNotEmpty() -> stringResource(MR.string.photobase_status_running)
    status.isAllBackedUp -> stringResource(MR.string.photobase_status_all_backed_up)
    else -> stringResource(MR.string.photobase_status_up_to_date)
}
