package id.homebase.core.ui.screens.calls.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Call
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
import id.homebase.resources.nav_calls
import id.homebase.resources.settings_calls_beta
import id.homebase.resources.settings_calls_beta_desc
import org.jetbrains.compose.resources.stringResource

@Composable
fun CallsSettingsScreen(
    viewModel: CallsSettingsViewModel,
    onBackClick: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    CallsSettingsUi(uiState = uiState, onAction = viewModel::onAction, onBackClick = onBackClick)
}

@Composable
fun CallsSettingsUi(
    uiState: CallsSettingsUiState,
    onAction: (CallsSettingsUiAction) -> Unit,
    onBackClick: () -> Unit,
) {
    SettingsDetailScaffold(
        title = stringResource(MR.string.nav_calls),
        onBack = onBackClick,
    ) {
        Spacer(modifier = Modifier.height(8.dp))
        SettingsRow(
            modifier = Modifier.testTag("callsBetaToggle"),
            icon = Icons.Outlined.Call,
            title = stringResource(MR.string.settings_calls_beta),
            supportingText = stringResource(MR.string.settings_calls_beta_desc),
            action = SettingsRowAction.Toggle(
                checked = uiState.enabled,
                onCheckedChange = { onAction(CallsSettingsUiAction.SetEnabled(it)) },
            ),
        )
        Spacer(modifier = Modifier.fillMaxWidth().height(24.dp))
    }
}
