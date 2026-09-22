package id.homebase.core.ui.screens.location

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.api.client.location.LocationPreviewProvider
import id.homebase.core.ui.screens.location.devices.DeviceRow
import id.homebase.core.ui.screens.location.history.LocationTraceCanvas
import id.homebase.core.ui.screens.location.history.mapTraceColors
import id.homebase.core.widget.SettingsSectionHeader
import id.homebase.resources.MR
import id.homebase.resources.location_dashboard_empty_today
import id.homebase.resources.location_dashboard_history_section
import id.homebase.resources.location_devices_section
import id.homebase.resources.location_history_overview_helper
import id.homebase.resources.location_history_overview_needs_permission
import id.homebase.resources.location_perm_open_settings
import id.homebase.resources.location_tracking_switch
import id.homebase.resources.location_tracking_unavailable
import id.homebase.resources.menu_back
import kotlin.uuid.Uuid
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocationHistoryOverviewScreen(
    viewModel: LocationViewModel,
    onNavigateBack: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenDevice: (Uuid) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val previewProvider = koinInject<LocationPreviewProvider>()

    LocationPermissionHost(viewModel = viewModel, uiState = uiState) { dispatch ->
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(MR.string.location_dashboard_history_section)) },
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(MR.string.menu_back),
                            )
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
                    .padding(horizontal = 16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Spacer(modifier = Modifier.height(0.dp))
                Text(
                    text = stringResource(MR.string.location_history_overview_helper),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(MR.string.location_tracking_switch),
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f),
                            )
                            Switch(
                                checked = uiState.allowLocationHistory,
                                onCheckedChange = { dispatch(LocationUiAction.SetAllowLocationHistory(it)) },
                                enabled = uiState.trackingAvailable &&
                                    (uiState.allowLocationHistory || uiState.whileInUseGranted),
                            )
                        }
                        if (!uiState.trackingAvailable) {
                            Text(
                                text = stringResource(MR.string.location_tracking_unavailable),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                            )
                        } else if (!uiState.whileInUseGranted) {
                            Text(
                                text = stringResource(MR.string.location_history_overview_needs_permission),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp),
                            )
                            TextButton(
                                onClick = onOpenSettings,
                                modifier = Modifier.padding(start = 8.dp, bottom = 4.dp),
                            ) {
                                Text(stringResource(MR.string.location_perm_open_settings))
                            }
                        }
                    }
                }

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                        .clickable(onClick = onOpenHistory),
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        if (uiState.todayTraces.isEmpty()) {
                            Text(
                                text = stringResource(MR.string.location_dashboard_empty_today),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.align(Alignment.Center).padding(24.dp),
                            )
                        } else {
                            LocationTraceCanvas(
                                traces = uiState.todayTraces,
                                showMapTiles = uiState.showMapTiles,
                                fetchTile = { z, x, y -> previewProvider.getTilePng(z, x, y) },
                                traceColors = mapTraceColors,
                                interactive = false,
                            )
                        }
                    }
                }

                SettingsSectionHeader(
                    title = stringResource(MR.string.location_devices_section),
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column {
                        uiState.devices.forEachIndexed { index, device ->
                            if (index > 0) HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                            DeviceRow(device = device, onClick = { onOpenDevice(device.deviceId) })
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}
