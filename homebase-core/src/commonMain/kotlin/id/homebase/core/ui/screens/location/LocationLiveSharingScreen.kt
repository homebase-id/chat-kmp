package id.homebase.core.ui.screens.location

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.LocationSearching
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.api.common.OdinId
import id.homebase.core.avatars.AvatarOptions
import id.homebase.core.avatars.PublicAvatar
import id.homebase.core.location.LIVE_SHARE_INDEFINITE
import id.homebase.core.ui.screens.location.livelocation.AGE_LABEL_AFTER_MS
import id.homebase.core.util.formatUntilTime
import id.homebase.core.widget.SettingsSectionHeader
import id.homebase.resources.MR
import id.homebase.resources.cancel
import id.homebase.resources.live_location_age_minutes
import id.homebase.resources.location_dashboard_live_empty
import id.homebase.resources.location_dashboard_live_open
import id.homebase.resources.location_dashboard_live_section
import id.homebase.resources.location_dashboard_share_until
import id.homebase.resources.location_dashboard_share_until_stopped
import id.homebase.resources.location_dashboard_sharing_with
import id.homebase.resources.location_dashboard_sharing_with_you
import id.homebase.resources.location_dashboard_stop_confirm_body
import id.homebase.resources.location_dashboard_stop_confirm_title
import id.homebase.resources.location_dashboard_stop_everyone
import id.homebase.resources.location_dashboard_stop_one_cd
import id.homebase.resources.location_live_helper
import id.homebase.resources.menu_back
import id.homebase.resources.stop_sharing
import kotlin.time.Instant
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocationLiveSharingScreen(
    viewModel: LocationViewModel,
    onNavigateBack: () -> Unit,
    onOpenLiveMap: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(MR.string.location_dashboard_live_section)) },
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
                text = stringResource(MR.string.location_live_helper),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (uiState.liveSharingVisible) {
                Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenLiveMap)) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.LocationOn,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = stringResource(MR.string.location_dashboard_live_open),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                if (uiState.outgoingShares.isNotEmpty()) {
                    var confirmStopAll by remember { mutableStateOf(false) }
                    SettingsSectionHeader(
                        title = stringResource(MR.string.location_dashboard_sharing_with),
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column {
                            uiState.outgoingShares.forEachIndexed { index, row ->
                                if (index > 0) HorizontalDivider()
                                OutgoingShareRowItem(
                                    row = row,
                                    onStop = { viewModel.onAction(LocationUiAction.StopSharingWith(row.odinId)) },
                                )
                            }
                        }
                    }
                    TextButton(onClick = { confirmStopAll = true }) {
                        Text(text = stringResource(MR.string.location_dashboard_stop_everyone))
                    }
                    if (confirmStopAll) {
                        AlertDialog(
                            onDismissRequest = { confirmStopAll = false },
                            title = { Text(stringResource(MR.string.location_dashboard_stop_confirm_title)) },
                            text = { Text(stringResource(MR.string.location_dashboard_stop_confirm_body)) },
                            confirmButton = {
                                TextButton(onClick = {
                                    confirmStopAll = false
                                    viewModel.onAction(LocationUiAction.StopSharingWithEveryone)
                                }) { Text(stringResource(MR.string.stop_sharing)) }
                            },
                            dismissButton = {
                                TextButton(onClick = { confirmStopAll = false }) {
                                    Text(stringResource(MR.string.cancel))
                                }
                            },
                        )
                    }
                }

                if (uiState.incomingShares.isNotEmpty()) {
                    SettingsSectionHeader(
                        title = stringResource(MR.string.location_dashboard_sharing_with_you),
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column {
                            uiState.incomingShares.forEachIndexed { index, row ->
                                if (index > 0) HorizontalDivider()
                                IncomingShareRowItem(row = row)
                            }
                        }
                    }
                }
            } else {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.LocationSearching,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = stringResource(MR.string.location_dashboard_live_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun OutgoingShareRowItem(
    row: OutgoingShareRow,
    onStop: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PublicAvatar(
            odinId = OdinId(row.odinId),
            initials = row.avatarInitials,
            options = AvatarOptions(size = 40.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.name,
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = if (row.untilMs == LIVE_SHARE_INDEFINITE) {
                    stringResource(MR.string.location_dashboard_share_until_stopped)
                } else {
                    stringResource(
                        MR.string.location_dashboard_share_until,
                        formatUntilTime(Instant.fromEpochMilliseconds(row.untilMs)),
                    )
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onStop) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = stringResource(MR.string.location_dashboard_stop_one_cd, row.name),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun IncomingShareRowItem(row: IncomingShareRow) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PublicAvatar(
            odinId = OdinId(row.odinId),
            initials = row.avatarInitials,
            options = AvatarOptions(size = 40.dp),
        )
        Text(
            text = row.name,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        if (row.ageMs > AGE_LABEL_AFTER_MS) {
            Text(
                text = stringResource(MR.string.live_location_age_minutes, (row.ageMs / 60_000L).toInt()),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
