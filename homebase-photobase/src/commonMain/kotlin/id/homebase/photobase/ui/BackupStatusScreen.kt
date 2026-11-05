package id.homebase.photobase.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.photobase.backup.BackupPauseReason
import id.homebase.photobase.backup.BackupStatus
import id.homebase.resources.MR
import id.homebase.resources.menu_back
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
import id.homebase.resources.photobase_status_retry
import id.homebase.resources.photobase_status_running
import id.homebase.resources.photobase_status_title
import id.homebase.resources.photobase_status_up_to_date
import id.homebase.resources.photobase_status_uploading
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.stringResource

@Composable
fun BackupStatusScreen(viewModel: BackupSettingsViewModel, onBack: () -> Unit) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val status = uiState.status
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(MR.string.photobase_status_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(MR.string.menu_back))
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .consumeWindowInsets(innerPadding)
                .padding(innerPadding)
                .verticalScroll(rememberScrollState()),
        ) {
            ListItem(headlineContent = { Text(backupStatusSummary(status)) })
            ListItem(
                headlineContent = { Text(stringResource(MR.string.photobase_status_last_success)) },
                supportingContent = {
                    Text(
                        status.lastSuccessMs?.let {
                            Instant.fromEpochMilliseconds(it).toLocalDateTime(TimeZone.currentSystemDefault()).toString().replace('T', ' ')
                        } ?: stringResource(MR.string.photobase_status_never),
                    )
                },
            )
            ListItem(
                headlineContent = { Text(stringResource(MR.string.photobase_status_backed_up)) },
                trailingContent = { Text(status.backedUpCount.toString()) },
            )
            ListItem(
                headlineContent = { Text(stringResource(MR.string.photobase_status_pending)) },
                trailingContent = { Text(status.pendingCount.toString()) },
            )
            ListItem(
                headlineContent = { Text(stringResource(MR.string.photobase_status_uploading)) },
                trailingContent = { Text(status.uploadingCount.toString()) },
            )
            if (status.failedCount > 0) {
                ListItem(
                    headlineContent = { Text(stringResource(MR.string.photobase_status_failures)) },
                    trailingContent = { Text(status.failedCount.toString()) },
                )
                for (failure in status.failures) {
                    ListItem(headlineContent = { Text(failure.reason) }, supportingContent = { Text(failure.deviceAssetId) })
                }
                Button(
                    onClick = { viewModel.onAction(BackupSettingsUiAction.RetryFailures) },
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                ) {
                    Text(stringResource(MR.string.photobase_status_retry))
                }
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
    else -> stringResource(MR.string.photobase_status_up_to_date)
}
