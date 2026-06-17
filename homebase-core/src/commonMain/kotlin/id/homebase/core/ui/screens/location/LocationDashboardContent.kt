package id.homebase.core.ui.screens.location

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import id.homebase.core.ui.screens.location.devices.LocationDeviceInfo
import id.homebase.core.ui.screens.location.history.LocationTraceCanvas
import id.homebase.core.util.formatTimestamp
import id.homebase.resources.MR
import id.homebase.resources.location_dashboard_empty_today
import id.homebase.resources.location_dashboard_history_section
import id.homebase.resources.location_dashboard_perm_banner
import id.homebase.resources.location_device_no_fix
import id.homebase.resources.location_device_this_device
import id.homebase.resources.location_device_unnamed
import id.homebase.resources.location_devices_section
import id.homebase.resources.location_status_pending
import id.homebase.resources.location_status_points_today
import kotlin.time.Instant
import kotlin.uuid.Uuid
import org.jetbrains.compose.resources.stringResource

/**
 * Everyday main screen once the Location add-on runs: map preview of today,
 * the device list (= the Find-device picker), and a compact status footer.
 * Future cards (Sentinel, live sharing) slot in below the device list.
 */
@Composable
fun LocationDashboardContent(
    uiState: LocationUiState,
    innerPadding: PaddingValues,
    fetchTile: suspend (zoom: Int, x: Int, y: Int) -> ByteArray?,
    onOpenHistory: () -> Unit,
    onOpenDevice: (Uuid) -> Unit,
    onOpenSetup: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .consumeWindowInsets(innerPadding)
            .padding(innerPadding)
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(modifier = Modifier.height(0.dp))

        // ── Degraded-permission banner ──
        if (uiState.trackingAvailable && !uiState.whileInUseGranted) {
            Card(
                modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenSetup),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ),
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.WarningAmber,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Text(
                        text = stringResource(MR.string.location_dashboard_perm_banner),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }
        }

        // ── Location History (map preview, today, all devices) → History ──
        Text(
            text = stringResource(MR.string.location_dashboard_history_section),
            style = MaterialTheme.typography.titleMedium,
        )
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
                        fetchTile = fetchTile,
                        traceColors = dashboardTraceColors(),
                        interactive = false,
                    )
                }
            }
        }

        // ── My devices (= Find device picker) ──
        Text(
            text = stringResource(MR.string.location_devices_section),
            style = MaterialTheme.typography.titleMedium,
        )
        Card(modifier = Modifier.fillMaxWidth()) {
            Column {
                uiState.devices.forEachIndexed { index, device ->
                    if (index > 0) HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    DeviceRow(device = device, onClick = { onOpenDevice(device.deviceId) })
                }
            }
        }

        // ── Footer ──
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(MR.string.location_status_points_today) +
                    ": ${uiState.pointsToday}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(MR.string.location_status_pending) +
                    ": ${uiState.pendingUploadCount}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun DeviceRow(device: LocationDeviceInfo, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = when (device.platform) {
                "desktop" -> Icons.Outlined.Computer
                "web" -> Icons.Outlined.Language
                else -> Icons.Outlined.Smartphone
            },
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(28.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = device.name
                    ?: stringResource(MR.string.location_device_unnamed, device.shortId),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = device.lastFix?.let {
                    formatTimestamp(Instant.fromEpochMilliseconds(it.t))
                } ?: stringResource(MR.string.location_device_no_fix),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (device.isThisDevice) {
            SuggestionChip(
                onClick = onClick,
                label = { Text(stringResource(MR.string.location_device_this_device)) },
            )
        }
    }
}

@Composable
fun dashboardTraceColors() = id.homebase.core.ui.screens.location.history.mapTraceColors
