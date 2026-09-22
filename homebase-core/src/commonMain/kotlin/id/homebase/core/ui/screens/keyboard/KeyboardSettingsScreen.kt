package id.homebase.core.ui.screens.keyboard

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardReturn
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.core.widget.SettingsRow
import id.homebase.core.widget.SettingsRowAction
import id.homebase.core.widget.SettingsTopBar
import id.homebase.resources.MR
import id.homebase.resources.settings_composer_arrow_up_edits
import id.homebase.resources.settings_composer_arrow_up_edits_description
import id.homebase.resources.settings_composer_enter_sends
import id.homebase.resources.settings_composer_enter_sends_description
import id.homebase.resources.settings_keyboard
import org.jetbrains.compose.resources.stringResource

@Composable
fun KeyboardSettingsScreen(
    viewModel: KeyboardSettingsViewModel,
    onBackClick: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    KeyboardSettingsUi(
        uiState = uiState,
        onAction = viewModel::onAction,
        onBackClick = onBackClick,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeyboardSettingsUi(
    uiState: KeyboardSettingsUiState,
    onAction: (KeyboardSettingsUiAction) -> Unit,
    onBackClick: () -> Unit,
) {
    Scaffold(
        topBar = {
            SettingsTopBar(
                title = stringResource(MR.string.settings_keyboard),
                onBack = onBackClick,
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .consumeWindowInsets(innerPadding)
                .padding(innerPadding)
                .verticalScroll(rememberScrollState()),
        ) {
            SettingsRow(
                modifier = Modifier.testTag("enterSendsToggle"),
                icon = Icons.AutoMirrored.Outlined.KeyboardReturn,
                title = stringResource(MR.string.settings_composer_enter_sends),
                supportingText = stringResource(MR.string.settings_composer_enter_sends_description),
                action = SettingsRowAction.Toggle(
                    checked = uiState.enterSendsMessage,
                    onCheckedChange = { onAction(KeyboardSettingsUiAction.SetEnterSendsMessage(it)) },
                ),
            )
            SettingsRow(
                modifier = Modifier.testTag("arrowUpEditsToggle"),
                icon = Icons.Outlined.Edit,
                title = stringResource(MR.string.settings_composer_arrow_up_edits),
                supportingText = stringResource(MR.string.settings_composer_arrow_up_edits_description),
                action = SettingsRowAction.Toggle(
                    checked = uiState.arrowUpEditsLastMessage,
                    onCheckedChange = { onAction(KeyboardSettingsUiAction.SetArrowUpEditsLastMessage(it)) },
                ),
            )
        }
    }
}
