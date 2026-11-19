package id.homebase.core.ui.screens.media

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Flip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.core.widget.SettingsRow
import id.homebase.core.widget.SettingsRowAction
import id.homebase.core.ui.screens.settings.SettingsDetailScaffold
import id.homebase.resources.MR
import id.homebase.resources.settings_media
import id.homebase.resources.settings_media_mirror_front
import id.homebase.resources.settings_media_mirror_front_description
import org.jetbrains.compose.resources.stringResource

@Composable
fun MediaSettingsScreen(
    viewModel: MediaSettingsViewModel,
    onBackClick: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    MediaSettingsUi(
        uiState = uiState,
        onAction = viewModel::onAction,
        onBackClick = onBackClick,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaSettingsUi(
    uiState: MediaSettingsUiState,
    onAction: (MediaSettingsUiAction) -> Unit,
    onBackClick: () -> Unit,
) {
    SettingsDetailScaffold(
        title = stringResource(MR.string.settings_media),
        onBack = onBackClick,
    ) {
        Spacer(modifier = Modifier.height(8.dp))
        SettingsRow(
            modifier = Modifier.testTag("mirrorFrontCameraToggle"),
            icon = Icons.Outlined.Flip,
            title = stringResource(MR.string.settings_media_mirror_front),
            supportingText = stringResource(MR.string.settings_media_mirror_front_description),
            action = SettingsRowAction.Toggle(
                checked = uiState.mirrorFrontCamera,
                onCheckedChange = {
                    onAction(MediaSettingsUiAction.SetMirrorFrontCamera(it))
                },
            ),
        )

        Spacer(modifier = Modifier.height(24.dp))
    }
}
