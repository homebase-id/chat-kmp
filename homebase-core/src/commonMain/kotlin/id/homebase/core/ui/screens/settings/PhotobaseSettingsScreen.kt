package id.homebase.core.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.core.widget.SettingsRow
import id.homebase.core.widget.SettingsRowAction
import id.homebase.core.widget.SettingsTopBar
import id.homebase.photobase.ui.PhotobaseSettingsUiAction
import id.homebase.photobase.ui.PhotobaseSettingsUiState
import id.homebase.photobase.ui.PhotobaseSettingsViewModel
import id.homebase.photobase.ui.PushIntervalRow
import id.homebase.resources.MR
import id.homebase.resources.photobase_backup_enable_desc
import id.homebase.resources.photobase_backup_title
import id.homebase.resources.photobase_label
import id.homebase.resources.settings_photobase_icon_desc
import org.jetbrains.compose.resources.stringResource

@Composable
fun PhotobaseSettingsScreen(
    viewModel: SettingsViewModel,
    photobaseViewModel: PhotobaseSettingsViewModel,
    onBack: () -> Unit,
    onOpenPhotoBackup: (() -> Unit)?,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val photobaseUiState by photobaseViewModel.uiState.collectAsStateWithLifecycle()
    PhotobaseSettingsUi(uiState, viewModel::onAction, photobaseUiState, photobaseViewModel::onAction, onBack, onOpenPhotoBackup)
}

@Composable
internal fun PhotobaseSettingsUi(
    uiState: SettingsUiState,
    onAction: (SettingsUiAction) -> Unit,
    photobaseUiState: PhotobaseSettingsUiState,
    onPhotobaseAction: (PhotobaseSettingsUiAction) -> Unit,
    onBack: () -> Unit,
    onOpenPhotoBackup: (() -> Unit)?,
) {
    Scaffold(
        topBar = { SettingsTopBar(title = stringResource(MR.string.photobase_label), onBack = onBack) },
    ) { innerPadding ->
        val rowColor = MaterialTheme.colorScheme.surfaceContainer
        Column(
            modifier = Modifier
                .fillMaxSize()
                .consumeWindowInsets(innerPadding)
                .padding(innerPadding)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = AppsContentMaxWidth)
                    .fillMaxWidth()
                    .padding(16.dp)
                    .clip(MaterialTheme.shapes.large),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                SettingsRow(
                    modifier = Modifier.testTag("photobaseIconToggle"),
                    icon = Icons.Outlined.PhotoLibrary,
                    title = stringResource(MR.string.settings_photobase_icon_desc),
                    containerColor = rowColor,
                    action = SettingsRowAction.Toggle(
                        checked = uiState.photobaseIconVisible,
                        onCheckedChange = { onAction(SettingsUiAction.SetPhotobaseIconVisible(it)) },
                    ),
                )
                PushIntervalRow(photobaseUiState, onPhotobaseAction)
                onOpenPhotoBackup?.let { open ->
                    SettingsRow(
                        modifier = Modifier.testTag("photoBackupSettingsButton"),
                        icon = Icons.Outlined.CloudUpload,
                        title = stringResource(MR.string.photobase_backup_title),
                        supportingText = stringResource(MR.string.photobase_backup_enable_desc),
                        containerColor = rowColor,
                        action = SettingsRowAction.Navigate(open),
                    )
                }
            }
        }
    }
}
