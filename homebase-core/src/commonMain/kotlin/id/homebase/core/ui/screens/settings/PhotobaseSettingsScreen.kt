package id.homebase.core.ui.screens.settings

import androidx.compose.animation.Crossfade
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
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import id.homebase.photobase.ui.BackupSettingsSection
import id.homebase.photobase.ui.PhotobaseCheckingScreen
import id.homebase.photobase.ui.PhotobaseFinishingScreen
import id.homebase.photobase.ui.PhotobaseOnboardingScreen
import id.homebase.photobase.ui.PhotobaseQualityConfirmScreen
import id.homebase.photobase.ui.PhotobaseSettingsViewModel
import id.homebase.photobase.ui.PhotobaseSetupLifecycle
import id.homebase.photobase.ui.PhotobaseSetupStep
import id.homebase.photobase.ui.PhotobaseSetupUiAction
import id.homebase.photobase.ui.PhotobaseSetupViewModel
import id.homebase.photobase.ui.PushIntervalRow
import id.homebase.resources.MR
import id.homebase.resources.photobase_label
import id.homebase.resources.photobase_settings_open
import id.homebase.resources.settings_photobase_icon_desc
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

internal class PhotoBackupLinks(val onOpenStatus: () -> Unit, val onOpenKeepOpen: () -> Unit)

@Composable
internal fun PhotobaseSettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    // Null where photo backup doesn't exist: desktop and web.
    backup: PhotoBackupLinks?,
    // Null when the page was opened from Photos itself.
    onOpenPhotos: (() -> Unit)?,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    if (backup == null) {
        PhotobaseSettingsUi(uiState, viewModel::onAction, onBack, onOpenPhotos, intervalRow = null, backupSection = null)
    } else {
        PhotobaseSetupGate(onDismiss = onBack) {
            val photobaseViewModel: PhotobaseSettingsViewModel = koinViewModel()
            val photobaseUiState by photobaseViewModel.uiState.collectAsStateWithLifecycle()
            PhotobaseSettingsUi(
                uiState = uiState,
                onAction = viewModel::onAction,
                onBack = onBack,
                onOpenPhotos = onOpenPhotos,
                intervalRow = { PushIntervalRow(photobaseUiState, photobaseViewModel::onAction) },
                backupSection = {
                    BackupSettingsSection(
                        viewModel = koinViewModel(),
                        onOpenStatus = backup.onOpenStatus,
                        onOpenKeepOpen = backup.onOpenKeepOpen,
                    )
                },
            )
        }
    }
}

@Composable
internal fun PhotobaseSetupGate(onDismiss: () -> Unit, installed: @Composable () -> Unit) {
    val viewModel: PhotobaseSetupViewModel = koinViewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    PhotobaseSetupLifecycle(viewModel)
    Crossfade(
        targetState = uiState.step,
        animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(),
    ) { step ->
        when (step) {
            PhotobaseSetupStep.Checking -> PhotobaseCheckingScreen(
                failed = uiState.checkFailed,
                onRetry = { viewModel.onAction(PhotobaseSetupUiAction.RetryCheck) },
            )
            PhotobaseSetupStep.Onboarding -> PhotobaseOnboardingScreen(viewModel = viewModel, onDismiss = onDismiss)
            PhotobaseSetupStep.Finishing -> PhotobaseFinishingScreen(
                onCancel = { viewModel.onAction(PhotobaseSetupUiAction.FinishingCancelled) },
            )
            PhotobaseSetupStep.ConfirmQuality -> PhotobaseQualityConfirmScreen(viewModel = viewModel)
            PhotobaseSetupStep.Installed -> installed()
        }
    }
}

@Composable
internal fun PhotobaseSettingsUi(
    uiState: SettingsUiState,
    onAction: (SettingsUiAction) -> Unit,
    onBack: () -> Unit,
    onOpenPhotos: (() -> Unit)?,
    intervalRow: (@Composable () -> Unit)?,
    backupSection: (@Composable () -> Unit)?,
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
                onOpenPhotos?.let { open ->
                    SettingsRow(
                        modifier = Modifier.testTag("photobaseOpenButton"),
                        icon = Icons.Outlined.PhotoLibrary,
                        title = stringResource(MR.string.photobase_settings_open),
                        containerColor = rowColor,
                        action = SettingsRowAction.Navigate(open),
                    )
                }
                NavigationBarSwitchRow(
                    modifier = Modifier.testTag("photobaseIconToggle"),
                    title = stringResource(MR.string.settings_photobase_icon_desc),
                    checked = uiState.photobaseIconVisible,
                    onCheckedChange = { onAction(SettingsUiAction.SetPhotobaseIconVisible(it)) },
                    icon = Icons.Outlined.PhotoLibrary,
                    containerColor = rowColor,
                )
                intervalRow?.invoke()
            }
            backupSection?.let { section ->
                Column(modifier = Modifier.widthIn(max = AppsContentMaxWidth).fillMaxWidth()) { section() }
            }
        }
    }
}
