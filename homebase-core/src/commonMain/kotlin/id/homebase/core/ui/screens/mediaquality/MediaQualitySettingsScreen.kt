package id.homebase.core.ui.screens.mediaquality

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.api.image.MediaQuality
import id.homebase.core.widget.SettingsOptionRow
import id.homebase.core.widget.SettingsSectionHeader
import id.homebase.resources.MR
import id.homebase.resources.menu_back
import id.homebase.resources.settings_media_quality
import id.homebase.resources.settings_media_quality_footer
import id.homebase.resources.settings_media_quality_header
import org.jetbrains.compose.resources.stringResource

@Composable
fun MediaQualitySettingsScreen(
    viewModel: MediaQualitySettingsViewModel,
    onBackClick: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    MediaQualitySettingsUi(
        uiState = uiState,
        onAction = viewModel::onAction,
        onBackClick = onBackClick,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaQualitySettingsUi(
    uiState: MediaQualitySettingsUiState,
    onAction: (MediaQualitySettingsUiAction) -> Unit,
    onBackClick: () -> Unit,
) {
    val scrollState = rememberScrollState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(MR.string.settings_media_quality)) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
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
                .padding(innerPadding)
                .verticalScroll(scrollState),
        ) {
            SettingsSectionHeader(
                title = stringResource(MR.string.settings_media_quality_header),
                modifier = Modifier.padding(horizontal = 16.dp),
            )

            MediaQuality.entries.forEach { quality ->
                SettingsOptionRow(
                    modifier = Modifier.testTag(quality.code),
                    label = stringResource(quality.label),
                    selected = quality == uiState.mediaQuality,
                    onClick = {
                        onAction(MediaQualitySettingsUiAction.SetMediaQuality(quality))
                    },
                )
            }

            Text(
                text = stringResource(MR.string.settings_media_quality_footer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
            )
        }
    }
}
