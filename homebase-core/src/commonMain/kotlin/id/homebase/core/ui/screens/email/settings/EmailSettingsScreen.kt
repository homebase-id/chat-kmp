package id.homebase.core.ui.screens.email.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.core.widget.SettingsRow
import id.homebase.core.widget.SettingsRowAction
import id.homebase.core.widget.SettingsTopBar
import id.homebase.resources.MR
import id.homebase.resources.email_settings_biometrics
import id.homebase.resources.email_settings_open
import id.homebase.resources.email_settings_section
import id.homebase.resources.email_settings_show_icon
import org.jetbrains.compose.resources.stringResource

@Composable
fun EmailSettingsScreen(
    viewModel: EmailSettingsViewModel,
    onBackClick: () -> Unit,
    onOpenEmail: () -> Unit,
    showOpenEmail: Boolean = true,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    EmailSettingsUi(
        uiState = uiState,
        onAction = { action ->
            if (action is EmailSettingsUiAction.OpenEmailClicked) onOpenEmail()
            else viewModel.onAction(action)
        },
        onBackClick = onBackClick,
        showOpenEmail = showOpenEmail,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmailSettingsUi(
    uiState: EmailSettingsUiState,
    onAction: (EmailSettingsUiAction) -> Unit,
    onBackClick: () -> Unit,
    showOpenEmail: Boolean = true,
) {
    val scrollState = rememberScrollState()
    Scaffold(
        topBar = {
            SettingsTopBar(
                title = stringResource(MR.string.email_settings_section),
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
            Spacer(modifier = Modifier.height(8.dp))
            if (showOpenEmail) {
                SettingsRow(
                    icon = Icons.Outlined.MailOutline,
                    title = stringResource(MR.string.email_settings_open),
                    action = SettingsRowAction.Navigate {
                        onAction(EmailSettingsUiAction.OpenEmailClicked)
                    },
                )
            }
            SettingsRow(
                icon = Icons.Outlined.Visibility,
                title = stringResource(MR.string.email_settings_show_icon),
                action = SettingsRowAction.Toggle(
                    checked = uiState.iconVisible,
                    onCheckedChange = { onAction(EmailSettingsUiAction.SetIconVisible(it)) },
                ),
            )
            SettingsRow(
                icon = Icons.Outlined.Fingerprint,
                title = stringResource(MR.string.email_settings_biometrics),
                action = SettingsRowAction.Toggle(
                    checked = uiState.biometricsEnabled,
                    onCheckedChange = { onAction(EmailSettingsUiAction.SetBiometricsEnabled(it)) },
                ),
            )
            Spacer(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(24.dp)
            )
        }
    }
}
