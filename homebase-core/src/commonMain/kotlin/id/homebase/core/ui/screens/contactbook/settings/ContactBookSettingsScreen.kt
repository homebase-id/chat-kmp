package id.homebase.core.ui.screens.contactbook.settings

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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Contacts
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.homebase.core.widget.SettingsItemAction
import id.homebase.resources.MR
import id.homebase.resources.contactbook_settings_biometrics
import id.homebase.resources.contactbook_settings_open
import id.homebase.resources.contactbook_settings_section
import id.homebase.resources.contactbook_settings_show_icon
import id.homebase.resources.menu_back
import org.jetbrains.compose.resources.stringResource

@Composable
fun ContactBookSettingsScreen(
    viewModel: ContactBookSettingsViewModel,
    onBackClick: () -> Unit,
    onOpenContacts: () -> Unit,
    showOpenContacts: Boolean = true,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    ContactBookSettingsUi(
        uiState = uiState,
        onAction = { action ->
            if (action is ContactBookSettingsUiAction.OpenContactsClicked) onOpenContacts()
            else viewModel.onAction(action)
        },
        onBackClick = onBackClick,
        showOpenContacts = showOpenContacts,
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
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(MR.string.contactbook_settings_section)) },
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
                .consumeWindowInsets(innerPadding)
                .padding(innerPadding)
                .verticalScroll(rememberScrollState()),
        ) {
            Spacer(modifier = Modifier.height(8.dp))
            if (showOpenContacts) {
                SettingsItemAction(
                    imageVector = Icons.Outlined.Contacts,
                    text = stringResource(MR.string.contactbook_settings_open),
                    onClick = { onAction(ContactBookSettingsUiAction.OpenContactsClicked) },
                )
            }
            SettingsItemAction(
                imageVector = Icons.Outlined.Visibility,
                text = stringResource(MR.string.contactbook_settings_show_icon),
                onClick = { onAction(ContactBookSettingsUiAction.SetIconVisible(!uiState.iconVisible)) },
                trailingContent = {
                    Switch(
                        checked = uiState.iconVisible,
                        onCheckedChange = { onAction(ContactBookSettingsUiAction.SetIconVisible(it)) },
                    )
                },
            )
            SettingsItemAction(
                imageVector = Icons.Outlined.Fingerprint,
                text = stringResource(MR.string.contactbook_settings_biometrics),
                onClick = { onAction(ContactBookSettingsUiAction.SetBiometricsEnabled(!uiState.biometricsEnabled)) },
                trailingContent = {
                    Switch(
                        checked = uiState.biometricsEnabled,
                        onCheckedChange = { onAction(ContactBookSettingsUiAction.SetBiometricsEnabled(it)) },
                    )
                },
            )
            Spacer(modifier = Modifier.fillMaxWidth().height(24.dp))
        }
    }
}
