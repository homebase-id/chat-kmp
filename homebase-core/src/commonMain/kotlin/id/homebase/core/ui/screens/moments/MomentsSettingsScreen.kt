package id.homebase.core.ui.screens.moments

import id.homebase.core.settings.SendQualityRows
import id.homebase.core.ui.screens.settings.IncomingMediaSaveRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.core.widget.SettingsRow
import id.homebase.core.widget.SettingsRowAction
import id.homebase.core.ui.screens.settings.SettingsDetailScaffold
import id.homebase.resources.MR
import id.homebase.resources.moments_settings_open
import id.homebase.resources.moments_settings_section
import id.homebase.resources.moments_settings_show_icon
import org.jetbrains.compose.resources.stringResource

@Composable
fun MomentsSettingsScreen(
    viewModel: MomentsSettingsViewModel,
    onBackClick: () -> Unit,
    onOpenMoments: (() -> Unit)?,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    MomentsSettingsUi(uiState = uiState, onAction = viewModel::onAction, onBackClick = onBackClick, onOpenMoments = onOpenMoments)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MomentsSettingsUi(
    uiState: MomentsSettingsUiState,
    onAction: (MomentsSettingsUiAction) -> Unit,
    onBackClick: () -> Unit,
    onOpenMoments: (() -> Unit)? = null,
) {
    SettingsDetailScaffold(
        title = stringResource(MR.string.moments_settings_section),
        onBack = onBackClick,
    ) {
        Spacer(modifier = Modifier.height(8.dp))
        onOpenMoments?.let { open ->
            SettingsRow(
                icon = Icons.Outlined.AutoAwesome,
                title = stringResource(MR.string.moments_settings_open),
                action = SettingsRowAction.Navigate(open),
            )
        }
        SettingsRow(
            icon = Icons.Outlined.Visibility,
            title = stringResource(MR.string.moments_settings_show_icon),
            action = SettingsRowAction.Toggle(
                checked = uiState.iconVisible,
                onCheckedChange = { onAction(MomentsSettingsUiAction.SetIconVisible(it)) },
            ),
        )
        SendQualityRows(quality = uiState.quality, onChange = { onAction(MomentsSettingsUiAction.SetQuality(it)) })
        if (uiState.savesInBackground) {
            IncomingMediaSaveRow(
                checked = uiState.autoSaveIncomingMedia,
                onCheckedChange = { onAction(MomentsSettingsUiAction.SetAutoSaveIncomingMedia(it)) },
            )
        }
        Spacer(modifier = Modifier
            .fillMaxWidth()
            .height(24.dp))
    }
}
