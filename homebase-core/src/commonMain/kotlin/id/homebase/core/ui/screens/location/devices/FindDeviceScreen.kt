package id.homebase.core.ui.screens.location.devices

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.api.client.location.LocationPreviewProvider
import id.homebase.core.ui.screens.location.DeviceRow
import id.homebase.core.ui.screens.location.dashboardTraceColors
import id.homebase.core.ui.screens.location.history.LocationTraceCanvas
import id.homebase.core.util.formatTimestamp
import id.homebase.resources.MR
import id.homebase.resources.location_device_unnamed
import id.homebase.resources.location_find_battery
import id.homebase.resources.location_find_freshness
import id.homebase.resources.location_find_last_seen
import id.homebase.resources.location_find_no_location
import id.homebase.resources.location_find_refresh
import id.homebase.resources.location_find_title
import id.homebase.resources.menu_back
import kotlin.time.Instant
import kotlin.uuid.Uuid
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

/**
 * Find device: with no [FindDeviceUiState.deviceId] this is the device picker;
 * with one it shows the device's last known position (today's trail, last
 * point emphasized) and an honest freshness note — the position is only as
 * current as the device's last upload plus this device's last sync.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FindDeviceScreen(
    viewModel: FindDeviceViewModel,
    onNavigateBack: () -> Unit,
    onOpenDevice: (Uuid) -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val previewProvider = koinInject<LocationPreviewProvider>()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        uiState.device?.let { d ->
                            d.name ?: stringResource(MR.string.location_device_unnamed, d.shortId)
                        } ?: stringResource(MR.string.location_find_title)
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(MR.string.menu_back),
                        )
                    }
                },
                actions = {
                    if (uiState.deviceId != null) {
                        IconButton(onClick = { viewModel.refresh() }) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = stringResource(MR.string.location_find_refresh),
                            )
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        if (uiState.deviceId == null) {
            // ── Picker ──
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .consumeWindowInsets(innerPadding)
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState()),
            ) {
                uiState.devices.forEachIndexed { index, device ->
                    if (index > 0) HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    DeviceRow(device = device, onClick = { onOpenDevice(device.deviceId) })
                }
                if (uiState.isLoading && uiState.devices.isEmpty()) {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.CenterHorizontally).padding(32.dp),
                    )
                }
            }
        } else {
            // ── Locate one device ──
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .consumeWindowInsets(innerPadding)
                    .padding(innerPadding),
            ) {
                val lastFix = uiState.device?.lastFix
                if (lastFix != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(
                                MR.string.location_find_last_seen,
                                formatTimestamp(Instant.fromEpochMilliseconds(lastFix.t)),
                            ),
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                        lastFix.bat?.let { battery ->
                            Text(
                                text = stringResource(MR.string.location_find_battery, "$battery%"),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                // Card clips and insets the map so it doesn't run into the
                // last-seen header above or the freshness note below.
                Card(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        val trace = uiState.deviceTrace
                        when {
                            uiState.isLoading && trace == null ->
                                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))

                            trace == null ->
                                Text(
                                    text = stringResource(MR.string.location_find_no_location),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                                )

                            else ->
                                LocationTraceCanvas(
                                    traces = listOf(trace),
                                    showMapTiles = uiState.showMapTiles,
                                    fetchTile = { z, x, y -> previewProvider.getTilePng(z, x, y) },
                                    traceColors = dashboardTraceColors(),
                                    highlightLast = true,
                                )
                        }
                    }
                }
                Text(
                    text = stringResource(MR.string.location_find_freshness),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
}
