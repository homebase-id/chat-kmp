package id.homebase.core.ui.screens.location

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.ShareLocation
import androidx.compose.material.icons.outlined.Sos
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import id.homebase.core.location.LocationMapProvider
import id.homebase.resources.MR
import id.homebase.resources.location_dashboard_history_section
import id.homebase.resources.location_dashboard_live_section
import id.homebase.resources.location_dashboard_perm_banner
import id.homebase.resources.location_map_none
import id.homebase.resources.location_map_osm
import id.homebase.resources.location_tile_emergency_stale
import id.homebase.resources.location_tile_emergency_stale_cd
import id.homebase.resources.location_tile_emergency_status
import id.homebase.resources.location_tile_emergency_title
import id.homebase.resources.location_device_unnamed
import id.homebase.resources.location_tile_history_status
import id.homebase.resources.location_tile_history_tracked_by
import id.homebase.resources.location_tile_history_viewer_off
import id.homebase.resources.location_tile_live_off
import id.homebase.resources.location_tile_live_status
import id.homebase.resources.location_tile_press_to_activate
import id.homebase.resources.location_tile_press_to_set_up
import id.homebase.resources.location_tile_settings_status
import id.homebase.resources.location_tile_settings_title
import kotlin.time.Clock
import org.jetbrains.compose.resources.stringResource

@Composable
fun LocationTileHomeContent(
    uiState: LocationUiState,
    innerPadding: PaddingValues,
    onOpenEmergency: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenLiveSharing: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val tiles = deriveLocationTiles(uiState, Clock.System.now().toEpochMilliseconds())
    val pressToSetUp = stringResource(MR.string.location_tile_press_to_set_up)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .consumeWindowInsets(innerPadding)
            .padding(innerPadding)
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.height(12.dp))
        Column(
            modifier = Modifier.widthIn(max = 560.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (uiState.trackingAvailable && !(uiState.whileInUseGranted && uiState.alwaysGranted)) {
                Card(
                    modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenSettings),
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

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LocationTile(
                    style = LocationTileStyle.Emergency,
                    icon = Icons.Outlined.Sos,
                    title = stringResource(MR.string.location_tile_emergency_title),
                    on = tiles.emergencyOn,
                    statusText = when {
                        tiles.staleCount > 0 ->
                            stringResource(MR.string.location_tile_emergency_stale, tiles.staleCount)
                        tiles.emergencyOn -> stringResource(
                            MR.string.location_tile_emergency_status,
                            tiles.canLocateCount,
                            tiles.canLocateMeCount,
                        )
                        else -> pressToSetUp
                    },
                    warningCount = tiles.staleCount,
                    warningContentDescription = stringResource(
                        MR.string.location_tile_emergency_stale_cd,
                        tiles.staleCount,
                    ),
                    onClick = onOpenEmergency,
                    modifier = Modifier.weight(1f).aspectRatio(1f),
                )
                LocationTile(
                    style = LocationTileStyle.History,
                    icon = Icons.Outlined.History,
                    title = stringResource(MR.string.location_dashboard_history_section),
                    on = tiles.historyOn,
                    statusText = when {
                        tiles.trackedDevice != null -> stringResource(
                            MR.string.location_tile_history_tracked_by,
                            tiles.trackedDevice.name
                                ?: stringResource(MR.string.location_device_unnamed, tiles.trackedDevice.shortId),
                            tiles.pointsToday,
                        )
                        tiles.historyOn -> stringResource(
                            MR.string.location_tile_history_status,
                            tiles.pointsToday,
                            tiles.deviceCount,
                        )
                        // Viewer devices can never track; don't invite a tap that leads nowhere.
                        uiState.trackingAvailable -> stringResource(MR.string.location_tile_press_to_activate)
                        else -> stringResource(MR.string.location_tile_history_viewer_off)
                    },
                    onClick = onOpenHistory,
                    modifier = Modifier.weight(1f).aspectRatio(1f),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LocationTile(
                    style = LocationTileStyle.Live,
                    icon = Icons.Outlined.ShareLocation,
                    title = stringResource(MR.string.location_dashboard_live_section),
                    on = tiles.liveOn,
                    statusText = if (tiles.liveOn) {
                        stringResource(
                            MR.string.location_tile_live_status,
                            tiles.outgoingCount,
                            tiles.incomingCount,
                        )
                    } else {
                        stringResource(MR.string.location_tile_live_off)
                    },
                    onClick = onOpenLiveSharing,
                    modifier = Modifier.weight(1f).aspectRatio(1f),
                )
                val mapLabel = stringResource(
                    when (uiState.mapProvider) {
                        LocationMapProvider.None -> MR.string.location_map_none
                        LocationMapProvider.OpenStreetMap -> MR.string.location_map_osm
                    }
                )
                LocationTile(
                    style = LocationTileStyle.Settings,
                    icon = Icons.Outlined.Settings,
                    title = stringResource(MR.string.location_tile_settings_title),
                    on = tiles.settingsOn,
                    statusText = if (tiles.settingsOn) {
                        stringResource(MR.string.location_tile_settings_status, mapLabel)
                    } else {
                        pressToSetUp
                    },
                    onClick = onOpenSettings,
                    modifier = Modifier.weight(1f).aspectRatio(1f),
                )
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
    }
}
