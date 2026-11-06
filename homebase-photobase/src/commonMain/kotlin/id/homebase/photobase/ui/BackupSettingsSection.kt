package id.homebase.photobase.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material.icons.outlined.PhotoAlbum
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.core.widget.SettingsRow
import id.homebase.core.widget.SettingsRowAction
import id.homebase.photobase.backup.LibraryAccess
import id.homebase.resources.MR
import id.homebase.resources.photobase_backup_albums
import id.homebase.resources.photobase_backup_albums_default
import id.homebase.resources.photobase_backup_albums_some
import id.homebase.resources.photobase_backup_charging_only
import id.homebase.resources.photobase_backup_dialog_cancel
import id.homebase.resources.photobase_backup_dialog_done
import id.homebase.resources.photobase_backup_enable
import id.homebase.resources.photobase_backup_enable_desc
import id.homebase.resources.photobase_backup_guidance
import id.homebase.resources.photobase_backup_now
import id.homebase.resources.photobase_backup_newest_first
import id.homebase.resources.photobase_backup_newest_first_desc
import id.homebase.resources.photobase_backup_only_new
import id.homebase.resources.photobase_backup_only_new_desc
import id.homebase.resources.photobase_backup_status
import id.homebase.resources.photobase_backup_videos_cellular
import id.homebase.resources.photobase_backup_wifi_only
import id.homebase.resources.photobase_battery_body
import id.homebase.resources.photobase_battery_open_settings
import id.homebase.resources.photobase_battery_phone_maker
import id.homebase.resources.photobase_battery_title
import id.homebase.resources.photobase_keep_open
import id.homebase.resources.photobase_library_allow
import id.homebase.resources.photobase_library_allow_all
import id.homebase.resources.photobase_library_limited
import id.homebase.resources.photobase_library_none
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

private const val PHONE_MAKER_GUIDE_URL = "https://dontkillmyapp.com/"

@Composable
fun BackupSettingsSection(
    viewModel: BackupSettingsViewModel,
    onOpenStatus: () -> Unit,
    onOpenKeepOpen: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val settings = uiState.settings
    val onAction = viewModel::onAction
    var showAlbums by remember { mutableStateOf(false) }

    val rowColor = MaterialTheme.colorScheme.surfaceContainer
    LibraryAccessRow(uiState.status.libraryAccess) { onAction(BackupSettingsUiAction.PermissionChanged) }
    RowGroup {
        SettingsRow(
            icon = Icons.Outlined.CloudUpload,
            title = stringResource(MR.string.photobase_backup_enable),
            supportingText = stringResource(MR.string.photobase_backup_enable_desc),
            containerColor = rowColor,
            action = SettingsRowAction.Toggle(settings.enabled) { onAction(BackupSettingsUiAction.SetEnabled(it)) },
        )
        SettingsRow(
            icon = Icons.Outlined.Info,
            title = stringResource(MR.string.photobase_backup_status),
            supportingText = backupStatusSummary(uiState.status, settings.enabled),
            containerColor = rowColor,
            action = SettingsRowAction.Navigate(onOpenStatus),
        )
    }
    if (settings.enabled) {
        OutlinedButton(
            onClick = { onAction(BackupSettingsUiAction.BackUpNow) },
            enabled = !uiState.status.isRunning,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        ) {
            Text(stringResource(MR.string.photobase_backup_now))
        }
        TextButton(
            onClick = onOpenKeepOpen,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 8.dp),
        ) {
            Text(stringResource(MR.string.photobase_keep_open))
        }
    }
    RowGroup {
        SettingsRow(
            icon = Icons.Outlined.PhotoAlbum,
            title = stringResource(MR.string.photobase_backup_albums),
            supportingText = if (settings.albumIds.isEmpty()) {
                stringResource(MR.string.photobase_backup_albums_default)
            } else {
                pluralStringResource(MR.plurals.photobase_backup_albums_some, settings.albumIds.size, settings.albumIds.size)
            },
            containerColor = rowColor,
            action = SettingsRowAction.Navigate {
                onAction(BackupSettingsUiAction.LoadAlbums)
                showAlbums = true
            },
        )
        SettingsRow(
            icon = Icons.Outlined.Wifi,
            title = stringResource(MR.string.photobase_backup_wifi_only),
            containerColor = rowColor,
            action = SettingsRowAction.Toggle(settings.wifiOnly) { onAction(BackupSettingsUiAction.SetWifiOnly(it)) },
        )
        SettingsRow(
            icon = Icons.Outlined.Videocam,
            title = stringResource(MR.string.photobase_backup_videos_cellular),
            containerColor = rowColor,
            action = SettingsRowAction.Toggle(settings.videosOnCellular) { onAction(BackupSettingsUiAction.SetVideosOnCellular(it)) },
        )
        SettingsRow(
            icon = Icons.Outlined.BatteryChargingFull,
            title = stringResource(MR.string.photobase_backup_charging_only),
            containerColor = rowColor,
            action = SettingsRowAction.Toggle(settings.chargingOnly) { onAction(BackupSettingsUiAction.SetChargingOnly(it)) },
        )
        SettingsRow(
            icon = Icons.Outlined.NewReleases,
            title = stringResource(MR.string.photobase_backup_only_new),
            supportingText = stringResource(MR.string.photobase_backup_only_new_desc),
            containerColor = rowColor,
            action = SettingsRowAction.Toggle(settings.onlyNewFromMs != null) { onAction(BackupSettingsUiAction.SetOnlyNewFromNow(it)) },
        )
        SettingsRow(
            icon = Icons.Outlined.SwapVert,
            title = stringResource(MR.string.photobase_backup_newest_first),
            supportingText = stringResource(MR.string.photobase_backup_newest_first_desc),
            containerColor = rowColor,
            action = SettingsRowAction.Toggle(settings.newestFirst) { onAction(BackupSettingsUiAction.SetNewestFirst(it)) },
        )
    }
    uiState.batteryGuidance?.let { guidance ->
        ListItem(
            headlineContent = { Text(stringResource(MR.string.photobase_battery_title)) },
            supportingContent = {
                Column {
                    Text(stringResource(MR.string.photobase_battery_body))
                    TextButton(onClick = guidance.openSettings) {
                        Text(stringResource(MR.string.photobase_battery_open_settings))
                    }
                    val uriHandler = LocalUriHandler.current
                    TextButton(onClick = { uriHandler.openUri(PHONE_MAKER_GUIDE_URL) }) {
                        Text(stringResource(MR.string.photobase_battery_phone_maker))
                    }
                }
            },
        )
    }
    Text(
        text = stringResource(MR.string.photobase_backup_guidance),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(16.dp),
    )

    if (showAlbums) {
        AlbumsDialog(
            uiState = uiState,
            onDismiss = { showAlbums = false },
            onConfirm = {
                onAction(BackupSettingsUiAction.SetAlbums(it))
                showAlbums = false
            },
        )
    }
}

@Composable
private fun LibraryAccessRow(access: LibraryAccess, onChanged: () -> Unit) {
    if (access == LibraryAccess.Full) return
    val requester = rememberBackupLibraryAccess(onChanged)
    ListItem(
        headlineContent = {
            Text(
                stringResource(if (access == LibraryAccess.None) MR.string.photobase_library_none else MR.string.photobase_library_limited),
            )
        },
        supportingContent = {
            Button(onClick = requester) {
                Text(stringResource(if (access == LibraryAccess.None) MR.string.photobase_library_allow else MR.string.photobase_library_allow_all))
            }
        },
    )
}

@Composable
private fun RowGroup(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
            .clip(MaterialTheme.shapes.large),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        content = content,
    )
}

@Composable
private fun AlbumsDialog(uiState: BackupSettingsUiState, onDismiss: () -> Unit, onConfirm: (Set<String>) -> Unit) {
    var selected by remember { mutableStateOf(uiState.settings.albumIds) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(MR.string.photobase_backup_albums)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                ListItem(
                    modifier = Modifier.selectable(selected = selected.isEmpty(), role = Role.Checkbox) { selected = emptySet() },
                    headlineContent = { Text(stringResource(MR.string.photobase_backup_albums_default)) },
                    leadingContent = { Checkbox(checked = selected.isEmpty(), onCheckedChange = null) },
                )
                for (album in uiState.albums) {
                    val isSelected = album.id in selected
                    ListItem(
                        modifier = Modifier.selectable(selected = isSelected, role = Role.Checkbox) {
                            selected = if (isSelected) selected - album.id else selected + album.id
                        },
                        headlineContent = { Text(album.name) },
                        leadingContent = { Checkbox(checked = isSelected, onCheckedChange = null) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selected) }) { Text(stringResource(MR.string.photobase_backup_dialog_done)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(MR.string.photobase_backup_dialog_cancel)) }
        },
    )
}

