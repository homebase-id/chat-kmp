package id.homebase.core.ui.screens.location

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import id.homebase.core.location.LocationMapProvider
import id.homebase.core.util.formatTimestamp
import id.homebase.resources.MR
import id.homebase.resources.location_history_map_disclosure
import id.homebase.resources.location_map_none
import id.homebase.resources.location_map_osm
import id.homebase.resources.location_map_section
import id.homebase.resources.location_perm_always
import id.homebase.resources.location_perm_always_hint
import id.homebase.resources.location_perm_grant
import id.homebase.resources.location_perm_granted
import id.homebase.resources.location_perm_open_settings
import id.homebase.resources.location_perm_section
import id.homebase.resources.location_perm_while_in_use
import id.homebase.resources.location_settings_show_icon
import id.homebase.resources.location_status_last_fix
import id.homebase.resources.location_status_last_flush
import id.homebase.resources.location_status_never
import id.homebase.resources.location_status_pending
import id.homebase.resources.location_status_points_today
import id.homebase.resources.location_status_section
import id.homebase.resources.location_tracking_switch
import id.homebase.resources.location_tracking_unavailable
import kotlin.time.Instant
import org.jetbrains.compose.resources.stringResource

@Composable
fun LocationContent(
    uiState: LocationUiState,
    innerPadding: PaddingValues,
    onAction: (LocationUiAction) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .consumeWindowInsets(innerPadding)
            .padding(innerPadding)
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Spacer(modifier = Modifier.height(0.dp))

        // ── Master switch ──
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
                        checked = uiState.trackingEnabled,
                        onCheckedChange = { onAction(LocationUiAction.SetTrackingEnabled(it)) },
                        enabled = uiState.trackingAvailable &&
                            (uiState.trackingEnabled || uiState.whileInUseGranted),
                    )
                }
                if (!uiState.trackingAvailable) {
                    Text(
                        text = stringResource(MR.string.location_tracking_unavailable),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                    )
                }
            }
        }

        // ── Permissions ──
        if (uiState.trackingAvailable) {
            SectionHeader(stringResource(MR.string.location_perm_section))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
                    PermissionRow(
                        label = stringResource(MR.string.location_perm_while_in_use),
                        granted = uiState.whileInUseGranted,
                        permanentlyDenied = uiState.whileInUsePermanentlyDenied,
                        requestEnabled = true,
                        onRequest = { onAction(LocationUiAction.RequestWhileInUseClicked) },
                        onOpenSettings = { onAction(LocationUiAction.OpenSystemSettingsClicked) },
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    PermissionRow(
                        label = stringResource(MR.string.location_perm_always),
                        granted = uiState.alwaysGranted,
                        permanentlyDenied = uiState.alwaysPermanentlyDenied,
                        // Android requires foreground location before background
                        // may even be requested; iOS escalation likewise.
                        requestEnabled = uiState.whileInUseGranted,
                        onRequest = { onAction(LocationUiAction.RequestAlwaysClicked) },
                        onOpenSettings = { onAction(LocationUiAction.OpenSystemSettingsClicked) },
                    )
                    if (!uiState.alwaysGranted) {
                        Text(
                            text = stringResource(MR.string.location_perm_always_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                        )
                    }
                }
            }
        }

        // ── Map ──
        SectionHeader(stringResource(MR.string.location_map_section))
        Card(modifier = Modifier.fillMaxWidth()) {
            Column {
                LocationMapProvider.entries.forEachIndexed { index, provider ->
                    if (index > 0) HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    MapOptionRow(
                        label = mapProviderLabel(provider),
                        selected = uiState.mapProvider == provider,
                        onSelect = { onAction(LocationUiAction.SetMapProvider(provider)) },
                    )
                }
            }
        }
        if (uiState.mapProvider == LocationMapProvider.OpenStreetMap) {
            Text(
                text = stringResource(MR.string.location_history_map_disclosure),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }

        // ── Display ──
        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(MR.string.location_settings_show_icon),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = uiState.iconVisible,
                    onCheckedChange = { onAction(LocationUiAction.SetIconVisible(it)) },
                )
            }
        }

        // ── Status ──
        SectionHeader(stringResource(MR.string.location_status_section))
        Card(modifier = Modifier.fillMaxWidth()) {
            Column {
                val never = stringResource(MR.string.location_status_never)
                StatusRow(
                    label = stringResource(MR.string.location_status_last_fix),
                    value = uiState.lastFixEpochMs?.let { t ->
                        val coords = if (uiState.lastFixLat != null && uiState.lastFixLon != null) {
                            " (${uiState.lastFixLat.format5()}, ${uiState.lastFixLon.format5()})"
                        } else ""
                        formatTimestamp(Instant.fromEpochMilliseconds(t)) + coords
                    } ?: never,
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                StatusRow(
                    label = stringResource(MR.string.location_status_points_today),
                    value = uiState.pointsToday.toString(),
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                StatusRow(
                    label = stringResource(MR.string.location_status_pending),
                    value = uiState.pendingUploadCount.toString(),
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                StatusRow(
                    label = stringResource(MR.string.location_status_last_flush),
                    value = uiState.lastFlushEpochMs?.let {
                        formatTimestamp(Instant.fromEpochMilliseconds(it))
                    } ?: never,
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}

@Composable
private fun PermissionRow(
    label: String,
    granted: Boolean,
    permanentlyDenied: Boolean,
    requestEnabled: Boolean,
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        when {
            granted -> Text(
                text = stringResource(MR.string.location_perm_granted),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )

            permanentlyDenied -> Button(onClick = onOpenSettings) {
                Text(stringResource(MR.string.location_perm_open_settings))
            }

            else -> Button(onClick = onRequest, enabled = requestEnabled) {
                Text(stringResource(MR.string.location_perm_grant))
            }
        }
    }
}

@Composable
private fun mapProviderLabel(provider: LocationMapProvider): String = when (provider) {
    LocationMapProvider.None -> stringResource(MR.string.location_map_none)
    LocationMapProvider.OpenStreetMap -> stringResource(MR.string.location_map_osm)
}

@Composable
private fun MapOptionRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

private fun Double.format5(): String {
    val scaled = kotlin.math.round(this * 1e5) / 1e5
    return scaled.toString()
}
