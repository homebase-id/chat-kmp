package id.homebase.core.ui.screens.media

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.api.image.MediaQuality
import id.homebase.core.widget.SettingsOptionRow
import id.homebase.core.widget.SettingsRow
import id.homebase.core.widget.SettingsRowAction
import id.homebase.core.widget.SettingsSectionHeader
import id.homebase.core.widget.SettingsTopBar
import id.homebase.resources.MR
import id.homebase.resources.settings_media
import id.homebase.resources.settings_media_autosave
import id.homebase.resources.settings_media_autosave_description
import id.homebase.resources.settings_media_autosave_header
import id.homebase.resources.settings_media_autosave_unmetered
import id.homebase.resources.settings_media_autosave_unmetered_description
import id.homebase.resources.settings_media_quality_footer
import id.homebase.resources.settings_media_quality_header
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
    val scrollState = rememberScrollState()

    Scaffold(
        topBar = {
            SettingsTopBar(
                title = stringResource(MR.string.settings_media),
                onBack = onBackClick,
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .consumeWindowInsets(innerPadding)
                .padding(innerPadding)
                .verticalScroll(scrollState),
        ) {
            SettingsSectionHeader(
                title = stringResource(MR.string.settings_media_quality_header),
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp),
            )
            Column(modifier = Modifier.selectableGroup()) {
                MediaQuality.entries.forEach { quality ->
                    SettingsOptionRow(
                        modifier = Modifier.testTag(quality.code),
                        label = stringResource(quality.label),
                        supportingText = stringResource(quality.description),
                        selected = quality == uiState.mediaQuality,
                        onClick = { onAction(MediaSettingsUiAction.SetMediaQuality(quality)) },
                    )
                }
            }
            Text(
                text = stringResource(MR.string.settings_media_quality_footer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )

            SettingsSectionHeader(
                title = stringResource(MR.string.settings_media_autosave_header),
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp),
            )
            SettingsRow(
                modifier = Modifier.testTag("autoSaveToggle"),
                icon = Icons.Outlined.SaveAlt,
                title = stringResource(MR.string.settings_media_autosave),
                supportingText = stringResource(MR.string.settings_media_autosave_description),
                action = SettingsRowAction.Toggle(
                    checked = uiState.autoSaveIncomingMedia,
                    onCheckedChange = {
                        onAction(MediaSettingsUiAction.SetAutoSaveIncomingMedia(it))
                    },
                ),
            )
            AnimatedVisibility(visible = uiState.autoSaveIncomingMedia) {
                SettingsRow(
                    modifier = Modifier.testTag("autoSaveUnmeteredToggle"),
                    icon = Icons.Outlined.Wifi,
                    title = stringResource(MR.string.settings_media_autosave_unmetered),
                    supportingText =
                        stringResource(MR.string.settings_media_autosave_unmetered_description),
                    action = SettingsRowAction.Toggle(
                        checked = uiState.autoSaveOnUnmeteredOnly,
                        onCheckedChange = {
                            onAction(MediaSettingsUiAction.SetAutoSaveOnUnmeteredOnly(it))
                        },
                    ),
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
