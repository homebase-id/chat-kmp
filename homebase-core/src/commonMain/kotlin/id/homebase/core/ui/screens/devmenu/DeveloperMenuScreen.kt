package id.homebase.core.ui.screens.devmenu

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import chat_kmp.homebase_common.BuildConfig
import id.homebase.api.client.diagnostics.LastKnownServerIp
import id.homebase.api.client.diagnostics.NetworkDiagnostics
import id.homebase.api.client.diagnostics.ProbeStage
import id.homebase.api.client.diagnostics.ProbeStatus
import id.homebase.api.client.diagnostics.ResolutionRung
import id.homebase.api.client.diagnostics.ResolutionSource
import id.homebase.core.clipboard.clipEntryOf
import id.homebase.core.ui.screens.help.HelpClickableRow
import id.homebase.core.ui.screens.help.HelpSectionHeader
import id.homebase.core.widget.CheckboxRow
import id.homebase.resources.MR
import id.homebase.resources.cancel
import id.homebase.resources.dev_menu_allow_ten_bit_video
import id.homebase.resources.dev_menu_allow_ten_bit_video_description
import id.homebase.resources.dev_menu_clear_data
import id.homebase.resources.dev_menu_force_logout
import id.homebase.resources.dev_menu_force_logout_confirm_action
import id.homebase.resources.dev_menu_force_logout_confirm_message
import id.homebase.resources.dev_menu_force_logout_confirm_title
import id.homebase.resources.dev_menu_crash_confirm_action
import id.homebase.resources.dev_menu_crash_confirm_message
import id.homebase.resources.dev_menu_crash_confirm_title
import id.homebase.resources.dev_menu_force_sync
import id.homebase.resources.dev_menu_network_captive_portal
import id.homebase.resources.dev_menu_network_copy
import id.homebase.resources.dev_menu_network_last_good_ip
import id.homebase.resources.dev_menu_network_last_good_ip_none
import id.homebase.resources.dev_menu_network_rung_doh
import id.homebase.resources.dev_menu_network_rung_last_ip
import id.homebase.resources.dev_menu_network_rung_system_dns
import id.homebase.resources.dev_menu_run_network_diagnostics
import id.homebase.resources.dev_menu_section_crashlytics
import id.homebase.resources.dev_menu_section_network
import id.homebase.resources.dev_menu_section_database
import id.homebase.resources.dev_menu_section_misc
import id.homebase.resources.dev_menu_section_sync
import id.homebase.resources.dev_menu_section_testing
import id.homebase.resources.dev_menu_section_video
import id.homebase.resources.dev_menu_test_notification
import id.homebase.resources.dev_menu_test_scheduled_push
import id.homebase.resources.dev_menu_test_temporal_read
import id.homebase.resources.dev_menu_title
import id.homebase.resources.dev_menu_trigger_test_crash
import id.homebase.resources.dev_menu_trigger_test_crash_description
import id.homebase.resources.menu_back
import kotlinx.coroutines.launch
import kotlin.time.Clock
import org.jetbrains.compose.resources.stringResource

@Composable
fun DeveloperMenuScreen(
    viewModel: DeveloperMenuViewModel,
    onBackClick: () -> Unit,
    onNavigateToScheduledPushTest: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(uiState.uiEvent) {
        when (val event = uiState.uiEvent) {
            null -> {}
            is DeveloperMenuUiEvent.Back -> {
                viewModel.eventConsumed()
                onBackClick()
            }

            is DeveloperMenuUiEvent.Error -> {
                viewModel.eventConsumed()
                scope.launch { snackbarHostState.showSnackbar(message = event.errorMessage) }
            }

            is DeveloperMenuUiEvent.Success -> {
                viewModel.eventConsumed()
                scope.launch { snackbarHostState.showSnackbar(message = event.message) }
            }
        }
    }

    DeveloperMenuUi(
        snackbarHostState = snackbarHostState,
        uiState = uiState,
        onAction = viewModel::onUiAction,
        onBackClick = onBackClick,
        onNavigateToScheduledPushTest = onNavigateToScheduledPushTest,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeveloperMenuUi(
    snackbarHostState: SnackbarHostState,
    uiState: DeveloperMenuUiState,
    onAction: (DeveloperMenuUiAction) -> Unit,
    onBackClick: () -> Unit,
    onNavigateToScheduledPushTest: () -> Unit,
) {
    val scrollState = rememberScrollState()
    var showCrashConfirm by remember { mutableStateOf(false) }
    var showForceLogoutConfirm by remember { mutableStateOf(false) }
    val clipboard = LocalClipboard.current
    val clipboardScope = rememberCoroutineScope()

    if (showCrashConfirm) {
        AlertDialog(
            onDismissRequest = { showCrashConfirm = false },
            title = { Text(stringResource(MR.string.dev_menu_crash_confirm_title)) },
            text = { Text(stringResource(MR.string.dev_menu_crash_confirm_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showCrashConfirm = false
                        onAction(DeveloperMenuUiAction.TriggerTestCrash)
                    },
                ) {
                    Text(
                        text = stringResource(MR.string.dev_menu_crash_confirm_action),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showCrashConfirm = false }) {
                    Text(stringResource(MR.string.cancel))
                }
            },
        )
    }

    if (showForceLogoutConfirm) {
        AlertDialog(
            onDismissRequest = { showForceLogoutConfirm = false },
            title = { Text(stringResource(MR.string.dev_menu_force_logout_confirm_title)) },
            text = { Text(stringResource(MR.string.dev_menu_force_logout_confirm_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showForceLogoutConfirm = false
                        onAction(DeveloperMenuUiAction.ForceLogout)
                    },
                ) {
                    Text(
                        text = stringResource(MR.string.dev_menu_force_logout_confirm_action),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showForceLogoutConfirm = false }) {
                    Text(stringResource(MR.string.cancel))
                }
            },
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(MR.string.dev_menu_title)) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(MR.string.menu_back)
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
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            HelpSectionHeader(title = stringResource(MR.string.dev_menu_section_misc))
            Text(
                text = BuildConfig.APP_BUILD_TIME,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))

            // Sync & Connection Section
            HelpSectionHeader(title = stringResource(MR.string.dev_menu_section_sync))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
                    HelpClickableRow(
                        label = stringResource(MR.string.dev_menu_force_sync),
                        showChevron = false,
                        onClick = { onAction(DeveloperMenuUiAction.ForceSyncAll) }
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))

            // Network Status Section — layered DNS/TCP/TLS/ping probe (issue #1078)
            NetworkStatusSection(
                isRunning = uiState.isRunningNetworkDiagnostic,
                lastKnownGoodIp = uiState.lastKnownGoodIp,
                diagnostics = uiState.networkDiagnostics,
                onRun = { onAction(DeveloperMenuUiAction.RunNetworkDiagnostics) },
                onCopy = { snapshot ->
                    clipboardScope.launch { clipboard.setClipEntry(clipEntryOf(snapshot)) }
                },
            )
            Spacer(modifier = Modifier.height(8.dp))

            // Database Section
            HelpSectionHeader(title = stringResource(MR.string.dev_menu_section_database))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
                    HelpClickableRow(
                        label = stringResource(MR.string.dev_menu_clear_data),
                        showChevron = false,
                        onClick = { onAction(DeveloperMenuUiAction.ClearAllData) }
                    )
                    HelpClickableRow(
                        label = stringResource(MR.string.dev_menu_force_logout),
                        showChevron = false,
                        onClick = { showForceLogoutConfirm = true }
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))

            // Testing Section
            HelpSectionHeader(title = stringResource(MR.string.dev_menu_section_testing))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
                    HelpClickableRow(
                        label = stringResource(MR.string.dev_menu_test_notification),
                        showChevron = false,
                        onClick = { onAction(DeveloperMenuUiAction.TestRichNotification) }
                    )
                    HelpClickableRow(
                        label = stringResource(MR.string.dev_menu_test_temporal_read),
                        showChevron = false,
                        onClick = { onAction(DeveloperMenuUiAction.TestTemporalLocationRead) }
                    )
                    HelpClickableRow(
                        label = stringResource(MR.string.dev_menu_test_scheduled_push),
                        showChevron = true,
                        onClick = onNavigateToScheduledPushTest
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))

            // Video Section
            HelpSectionHeader(title = stringResource(MR.string.dev_menu_section_video))
            CheckboxRow(
                modifier = Modifier.fillMaxWidth(),
                label = stringResource(MR.string.dev_menu_allow_ten_bit_video),
                checked = uiState.allowTenBitVideo,
                onCheckedChange = { onAction(DeveloperMenuUiAction.ToggleAllowTenBitVideo) }
            )
            Text(
                text = stringResource(MR.string.dev_menu_allow_ten_bit_video_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))

            // Crashlytics Section
            HelpSectionHeader(title = stringResource(MR.string.dev_menu_section_crashlytics))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
                    HelpClickableRow(
                        label = stringResource(MR.string.dev_menu_trigger_test_crash),
                        showChevron = false,
                        onClick = { showCrashConfirm = true }
                    )
                }
            }
            Text(
                text = stringResource(MR.string.dev_menu_trigger_test_crash_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

/**
 * Layered network probe result panel (issue #1078): a Run button, a spinner while probing, one row
 * per DNS/TCP/TLS/ping stage colored by outcome, a captive-portal warning, and a copy-to-clipboard
 * button so the whole snapshot can be pasted into an issue.
 */
@Composable
private fun NetworkStatusSection(
    isRunning: Boolean,
    lastKnownGoodIp: LastKnownServerIp?,
    diagnostics: NetworkDiagnostics?,
    onRun: () -> Unit,
    onCopy: (String) -> Unit,
) {
    HelpSectionHeader(title = stringResource(MR.string.dev_menu_section_network))
    Card(modifier = Modifier.fillMaxWidth()) {
        Column {
            HelpClickableRow(
                label = stringResource(MR.string.dev_menu_run_network_diagnostics),
                showChevron = false,
                onClick = onRun,
            )

            // Always visible — the production-captured last-known-good IP, so it's confirmable
            // without running the probe.
            val ipValue = if (lastKnownGoodIp != null) {
                val now = Clock.System.now().toEpochMilliseconds()
                "${lastKnownGoodIp.ip} (${formatAge(now - lastKnownGoodIp.resolvedAtMs)})"
            } else {
                stringResource(MR.string.dev_menu_network_last_good_ip_none)
            }
            Text(
                text = stringResource(MR.string.dev_menu_network_last_good_ip, ipValue),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )

            if (isRunning) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                }
            }

            diagnostics?.let { d ->
                HorizontalDivider()
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    val serverLine = "Server: ${d.hostname}"
                    Text(
                        text = serverLine,
                        style = MaterialTheme.typography.titleSmall,
                    )

                    d.rungs.forEach { rung -> NetworkRungBlock(rung) }

                    if (d.captivePortalSuspected) {
                        Text(
                            text = stringResource(MR.string.dev_menu_network_captive_portal),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = { onCopy(buildNetworkSnapshot(d)) }) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = stringResource(MR.string.dev_menu_network_copy),
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(text = stringResource(MR.string.dev_menu_network_copy))
                        }
                    }
                }
            }
        }
    }
}

/** One resolution-ladder rung: a source header (resolve status + IP), then its connect stages. */
@Composable
private fun NetworkRungBlock(rung: ResolutionRung) {
    val rungLabel = when (rung.source) {
        ResolutionSource.SystemDns -> stringResource(MR.string.dev_menu_network_rung_system_dns)
        ResolutionSource.DoH -> stringResource(MR.string.dev_menu_network_rung_doh)
        ResolutionSource.LastKnownIp -> stringResource(MR.string.dev_menu_network_rung_last_ip)
    }
    val timing = rung.resolveMs?.let { " · ${it}ms" }.orEmpty()
    val header = "$rungLabel   ${rung.resolveStatus}$timing"
    val headerColor = when (rung.resolveStatus) {
        ProbeStatus.OK -> MaterialTheme.colorScheme.primary
        ProbeStatus.FAIL -> MaterialTheme.colorScheme.error
        ProbeStatus.SKIPPED -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = header,
            style = MaterialTheme.typography.titleSmall,
            color = headerColor,
        )
        Text(
            text = rung.resolveDetail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        rung.stages.forEach { stage -> NetworkStageRow(stage) }
    }
}

@Composable
private fun NetworkStageRow(stage: ProbeStage) {
    val timing = stage.durationMs?.let { " · ${it}ms" }.orEmpty()
    val header = "    ${stage.name}   ${stage.status}$timing"
    val headerColor = when (stage.status) {
        ProbeStatus.OK -> MaterialTheme.colorScheme.primary
        ProbeStatus.FAIL -> MaterialTheme.colorScheme.error
        ProbeStatus.SKIPPED -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column {
        Text(
            text = header,
            style = MaterialTheme.typography.bodyMedium,
            color = headerColor,
        )
        val indentedDetail = "    ${stage.detail}"
        Text(
            text = indentedDetail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Compact human age for the last-known-IP capture time (dev-only, not localized). */
private fun formatAge(ms: Long): String = when {
    ms < 0 -> "just now"
    ms < 60_000 -> "just now"
    ms < 3_600_000 -> "${ms / 60_000}m ago"
    ms < 86_400_000 -> "${ms / 3_600_000}h ago"
    else -> "${ms / 86_400_000}d ago"
}

/** Plain-text snapshot of a probe run for the clipboard / issue paste. */
private fun buildNetworkSnapshot(d: NetworkDiagnostics): String = buildString {
    appendLine("Network diagnostics — ${d.hostname}")
    d.rungs.forEach { rung ->
        val rungTiming = rung.resolveMs?.let { " (${it}ms)" }.orEmpty()
        appendLine("[${rung.source}] ${rung.resolveStatus}$rungTiming — ${rung.resolveDetail}")
        rung.stages.forEach { s ->
            val timing = s.durationMs?.let { " (${it}ms)" }.orEmpty()
            appendLine("  - ${s.name}: ${s.status}$timing — ${s.detail}")
        }
    }
    if (d.captivePortalSuspected) appendLine("Captive portal suspected")
    if (!d.supported) appendLine("(Network diagnostics unsupported on this platform)")
}