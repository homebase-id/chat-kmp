package id.homebase.core.ui.screens.filesync.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.core.widget.SettingsRow
import id.homebase.core.widget.SettingsRowAction
import id.homebase.core.ui.screens.settings.SettingsDetailScaffold
import id.homebase.core.ui.assets.Filesync
import id.homebase.core.ui.assets.HomebaseIcons
import id.homebase.resources.MR
import id.homebase.resources.filesync_settings_open
import id.homebase.resources.filesync_label
import id.homebase.resources.settings_filesync_icon_desc
import org.jetbrains.compose.resources.stringResource

@Composable
fun FilesyncSettingsScreen(
    viewModel: FilesyncSettingsViewModel,
    onBackClick: () -> Unit,
    onOpenDrive: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    FilesyncSettingsUi(
        uiState = uiState,
        onAction = { action ->
            if (action is FilesyncSettingsUiAction.OpenDriveClicked) onOpenDrive() else viewModel.onAction(action)
        },
        onBackClick = onBackClick,
    )
}

@Composable
fun FilesyncSettingsUi(
    uiState: FilesyncSettingsUiState,
    onAction: (FilesyncSettingsUiAction) -> Unit,
    onBackClick: () -> Unit,
) {
    SettingsDetailScaffold(
        title = stringResource(MR.string.filesync_label),
        onBack = onBackClick,
    ) {
        Spacer(modifier = Modifier.height(8.dp))
        SettingsRow(
            modifier = Modifier.testTag("filesyncOpenButton"),
            icon = HomebaseIcons.Filesync,
            title = stringResource(MR.string.filesync_settings_open),
            action = SettingsRowAction.Navigate { onAction(FilesyncSettingsUiAction.OpenDriveClicked) },
        )
        SettingsRow(
            modifier = Modifier.testTag("filesyncIconToggle"),
            icon = Icons.Outlined.Visibility,
            title = stringResource(MR.string.settings_filesync_icon_desc),
            action = SettingsRowAction.Toggle(
                checked = uiState.iconVisible,
                onCheckedChange = { onAction(FilesyncSettingsUiAction.SetIconVisible(it)) },
            ),
        )
        Spacer(modifier = Modifier.fillMaxWidth().height(24.dp))
    }
}
