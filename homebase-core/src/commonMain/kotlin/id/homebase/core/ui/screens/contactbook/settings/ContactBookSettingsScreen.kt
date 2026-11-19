package id.homebase.core.ui.screens.contactbook.settings

import id.homebase.core.ui.screens.settings.NavigationBarSwitchRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.People
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
import id.homebase.resources.contactbook_settings_open
import id.homebase.resources.contactbook_settings_section
import id.homebase.resources.contactbook_settings_show_icon
import org.jetbrains.compose.resources.stringResource

@Composable
fun ContactBookSettingsScreen(
    viewModel: ContactBookSettingsViewModel,
    onBackClick: () -> Unit,
    // Null when there's nothing to open: the page was opened from Contacts itself.
    onOpenContacts: (() -> Unit)?,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    ContactBookSettingsUi(
        uiState = uiState,
        onAction = { action ->
            if (action is ContactBookSettingsUiAction.OpenContactsClicked) onOpenContacts?.invoke()
            else viewModel.onAction(action)
        },
        onBackClick = onBackClick,
        showOpenContacts = onOpenContacts != null,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactBookSettingsUi(
    uiState: ContactBookSettingsUiState,
    onAction: (ContactBookSettingsUiAction) -> Unit,
    onBackClick: () -> Unit,
    showOpenContacts: Boolean = true,
) {
    SettingsDetailScaffold(
        title = stringResource(MR.string.contactbook_settings_section),
        onBack = onBackClick,
    ) {
        Spacer(modifier = Modifier.height(8.dp))
        if (showOpenContacts) {
            SettingsRow(
                icon = Icons.Outlined.People,
                title = stringResource(MR.string.contactbook_settings_open),
                action = SettingsRowAction.Navigate {
                    onAction(ContactBookSettingsUiAction.OpenContactsClicked)
                },
            )
        }
        NavigationBarSwitchRow(
            title = stringResource(MR.string.contactbook_settings_show_icon),
            checked = uiState.iconVisible,
            onCheckedChange = { onAction(ContactBookSettingsUiAction.SetIconVisible(it)) },
        )
        Spacer(modifier = Modifier.fillMaxWidth().height(24.dp))
    }
}
