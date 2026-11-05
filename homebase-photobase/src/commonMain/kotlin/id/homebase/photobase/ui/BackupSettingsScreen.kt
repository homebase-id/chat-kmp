package id.homebase.photobase.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.photobase.backup.LibraryAccess
import id.homebase.core.widget.SettingsTopBar
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
import id.homebase.resources.photobase_backup_only_new
import id.homebase.resources.photobase_backup_only_new_desc
import id.homebase.resources.photobase_backup_status
import id.homebase.resources.photobase_backup_title
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
fun BackupSettingsScreen(
    viewModel: BackupSettingsViewModel,
    onBack: () -> Unit,
    onOpenStatus: () -> Unit,
    onOpenKeepOpen: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val settings = uiState.settings
    val onAction = viewModel::onAction
    var showAlbums by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            SettingsTopBar(title = stringResource(MR.string.photobase_backup_title), onBack = onBack)
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .consumeWindowInsets(innerPadding)
                .padding(innerPadding)
                .verticalScroll(rememberScrollState()),
        ) {
            ToggleRow(
                title = stringResource(MR.string.photobase_backup_enable),
                supporting = stringResource(MR.string.photobase_backup_enable_desc),
                checked = settings.enabled,
                onChange = { onAction(BackupSettingsUiAction.SetEnabled(it)) },
            )
            LibraryAccessRow(uiState.status.libraryAccess) { onAction(BackupSettingsUiAction.PermissionChanged) }
            ListItem(
                modifier = Modifier.clickable(onClick = onOpenStatus),
                headlineContent = { Text(stringResource(MR.string.photobase_backup_status)) },
                supportingContent = { Text(backupStatusSummary(uiState.status)) },
            )
            if (settings.enabled) {
                OutlinedButton(
                    onClick = { onAction(BackupSettingsUiAction.BackUpNow) },
                    enabled = !uiState.status.isRunning,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text(stringResource(MR.string.photobase_backup_now))
                }
                TextButton(
                    onClick = onOpenKeepOpen,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                ) {
                    Text(stringResource(MR.string.photobase_keep_open))
                }
            }
            HorizontalDivider()
            ListItem(
                modifier = Modifier.clickable {
                    onAction(BackupSettingsUiAction.LoadAlbums)
                    showAlbums = true
                },
                headlineContent = { Text(stringResource(MR.string.photobase_backup_albums)) },
                supportingContent = {
                    Text(
                        if (settings.albumIds.isEmpty()) {
                            stringResource(MR.string.photobase_backup_albums_default)
                        } else {
                            pluralStringResource(MR.plurals.photobase_backup_albums_some, settings.albumIds.size, settings.albumIds.size)
                        },
                    )
                },
            )
            ToggleRow(
                title = stringResource(MR.string.photobase_backup_wifi_only),
                checked = settings.wifiOnly,
                onChange = { onAction(BackupSettingsUiAction.SetWifiOnly(it)) },
            )
            ToggleRow(
                title = stringResource(MR.string.photobase_backup_videos_cellular),
                checked = settings.videosOnCellular,
                onChange = { onAction(BackupSettingsUiAction.SetVideosOnCellular(it)) },
            )
            ToggleRow(
                title = stringResource(MR.string.photobase_backup_charging_only),
                checked = settings.chargingOnly,
                onChange = { onAction(BackupSettingsUiAction.SetChargingOnly(it)) },
            )
            ToggleRow(
                title = stringResource(MR.string.photobase_backup_only_new),
                supporting = stringResource(MR.string.photobase_backup_only_new_desc),
                checked = settings.onlyNewFromMs != null,
                onChange = { onAction(BackupSettingsUiAction.SetOnlyNewFromNow(it)) },
            )
            uiState.batteryGuidance?.takeIf { it.isRestricted }?.let { guidance ->
                HorizontalDivider()
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
            HorizontalDivider()
            Text(
                text = stringResource(MR.string.photobase_backup_guidance),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
    }

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
private fun ToggleRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit, supporting: String? = null) {
    ListItem(
        modifier = Modifier.toggleable(value = checked, onValueChange = onChange, role = Role.Switch),
        headlineContent = { Text(title) },
        supportingContent = supporting?.let { { Text(it) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
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

