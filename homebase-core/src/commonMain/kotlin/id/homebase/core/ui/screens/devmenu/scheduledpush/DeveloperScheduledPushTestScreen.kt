package id.homebase.core.ui.screens.devmenu.scheduledpush

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.api.client.notifications.ScheduledPushNotificationEntry
import id.homebase.api.client.notifications.ScheduledPushNotificationProvider
import id.homebase.api.client.notifications.ScheduledPushNotificationState
import id.homebase.resources.MR
import id.homebase.resources.cancel
import id.homebase.resources.menu_back
import id.homebase.resources.scheduled_push_test_cancel_confirm_action
import id.homebase.resources.scheduled_push_test_cancel_confirm_message_one_shot
import id.homebase.resources.scheduled_push_test_cancel_confirm_message_recurring
import id.homebase.resources.scheduled_push_test_cancel_confirm_title
import id.homebase.resources.scheduled_push_test_empty
import id.homebase.resources.scheduled_push_test_recurring_confirm_action
import id.homebase.resources.scheduled_push_test_recurring_confirm_message
import id.homebase.resources.scheduled_push_test_recurring_confirm_title
import id.homebase.resources.scheduled_push_test_refresh
import id.homebase.resources.scheduled_push_test_reschedule
import id.homebase.resources.scheduled_push_test_schedule_one_shot
import id.homebase.resources.scheduled_push_test_schedule_recurring
import id.homebase.resources.scheduled_push_test_title
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

@Composable
fun DeveloperScheduledPushTestScreen(
    viewModel: DeveloperScheduledPushTestViewModel,
    onBackClick: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(uiState.uiEvent) {
        when (val event = uiState.uiEvent) {
            null -> {}
            is DeveloperScheduledPushTestUiEvent.Error -> {
                viewModel.eventConsumed()
                scope.launch { snackbarHostState.showSnackbar(message = event.message) }
            }

            is DeveloperScheduledPushTestUiEvent.Success -> {
                viewModel.eventConsumed()
                scope.launch { snackbarHostState.showSnackbar(message = event.message) }
            }
        }
    }

    ScheduledPushTestUi(
        snackbarHostState = snackbarHostState,
        uiState = uiState,
        onAction = viewModel::onUiAction,
        onBackClick = onBackClick,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduledPushTestUi(
    snackbarHostState: SnackbarHostState,
    uiState: DeveloperScheduledPushTestUiState,
    onAction: (DeveloperScheduledPushTestUiAction) -> Unit,
    onBackClick: () -> Unit,
) {
    val scrollState = rememberScrollState()
    var showRecurringConfirm by remember { mutableStateOf(false) }
    var cancelTarget by remember { mutableStateOf<ScheduledPushNotificationEntry?>(null) }

    if (showRecurringConfirm) {
        AlertDialog(
            onDismissRequest = { showRecurringConfirm = false },
            title = { Text(stringResource(MR.string.scheduled_push_test_recurring_confirm_title)) },
            text = { Text(stringResource(MR.string.scheduled_push_test_recurring_confirm_message)) },
            confirmButton = {
                TextButton(onClick = {
                    showRecurringConfirm = false
                    onAction(DeveloperScheduledPushTestUiAction.ScheduleRecurring)
                }) {
                    Text(stringResource(MR.string.scheduled_push_test_recurring_confirm_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRecurringConfirm = false }) {
                    Text(stringResource(MR.string.cancel))
                }
            },
        )
    }

    cancelTarget?.let { entry ->
        AlertDialog(
            onDismissRequest = { cancelTarget = null },
            title = { Text(stringResource(MR.string.scheduled_push_test_cancel_confirm_title)) },
            text = {
                Text(
                    if (entry.recurrenceInterval != null) {
                        stringResource(MR.string.scheduled_push_test_cancel_confirm_message_recurring)
                    } else {
                        stringResource(MR.string.scheduled_push_test_cancel_confirm_message_one_shot)
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    cancelTarget = null
                    onAction(DeveloperScheduledPushTestUiAction.Cancel(entry.jobId))
                }) {
                    Text(
                        text = stringResource(MR.string.scheduled_push_test_cancel_confirm_action),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { cancelTarget = null }) {
                    Text(stringResource(MR.string.cancel))
                }
            },
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(MR.string.scheduled_push_test_title)) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(MR.string.menu_back)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { onAction(DeveloperScheduledPushTestUiAction.Refresh) }) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = stringResource(MR.string.scheduled_push_test_refresh)
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .consumeWindowInsets(innerPadding)
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    enabled = !uiState.isSubmitting,
                    onClick = { onAction(DeveloperScheduledPushTestUiAction.ScheduleOneShot) },
                ) {
                    Text(stringResource(MR.string.scheduled_push_test_schedule_one_shot))
                }
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    enabled = !uiState.isSubmitting,
                    onClick = { showRecurringConfirm = true },
                ) {
                    Text(stringResource(MR.string.scheduled_push_test_schedule_recurring))
                }
            }

            if (uiState.isSubmitting) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                }
            }

            HorizontalDivider()

            when {
                uiState.isLoading -> Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                }

                uiState.entries.isEmpty() -> Text(
                    text = stringResource(MR.string.scheduled_push_test_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                else -> uiState.entries.forEach { entry ->
                    ScheduledPushEntryCard(
                        entry = entry,
                        onReschedule = { onAction(DeveloperScheduledPushTestUiAction.Reschedule(entry)) },
                        onCancel = { cancelTarget = entry },
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

/**
 * Diagnostic detail lines (job id, state, timestamps, attempts) are dev-only and intentionally not
 * localized — same convention as the network-diagnostics panel elsewhere in this screen's parent,
 * [id.homebase.core.ui.screens.devmenu.DeveloperMenuScreen].
 */
@Composable
private fun ScheduledPushEntryCard(
    entry: ScheduledPushNotificationEntry,
    onReschedule: () -> Unit,
    onCancel: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "Job: ${entry.jobId}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "State: ${entry.state}   Attempts: ${entry.attemptCount}/${entry.maxAttempts}",
                style = MaterialTheme.typography.bodySmall,
                color = stateColor(entry.state),
            )
            Text(
                text = "Sends: ${entry.sendAt.iso8601()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = entry.recurrenceInterval?.let { "Repeats every ${it / 1000}s" } ?: "One-shot",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = onReschedule) {
                    Text(stringResource(MR.string.scheduled_push_test_reschedule))
                }
                OutlinedButton(onClick = onCancel) {
                    Text(
                        text = stringResource(MR.string.cancel),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun stateColor(state: ScheduledPushNotificationState) = when (state) {
    ScheduledPushNotificationState.Failed -> MaterialTheme.colorScheme.error
    ScheduledPushNotificationState.Succeeded -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}
