package id.homebase.core.ui.screens.vault

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
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import id.homebase.resources.menu_back
import id.homebase.resources.vault_settings_biometrics
import id.homebase.resources.vault_settings_open
import id.homebase.resources.vault_settings_section
import id.homebase.resources.vault_settings_show_icon
import org.jetbrains.compose.resources.stringResource

@Composable
fun VaultSettingsScreen(
    viewModel: VaultSettingsViewModel,
    onBackClick: () -> Unit,
    onOpenVault: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    VaultSettingsUi(
        uiState = uiState,
        onAction = { action ->
            if (action is VaultSettingsUiAction.OpenVaultClicked) onOpenVault()
            else viewModel.onAction(action)
        },
        onBackClick = onBackClick,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultSettingsUi(
    uiState: VaultSettingsUiState,
    onAction: (VaultSettingsUiAction) -> Unit,
    onBackClick: () -> Unit,
) {
    val scrollState = rememberScrollState()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(MR.string.vault_settings_section)) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.Default.ChevronLeft,
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
                .verticalScroll(scrollState),
        ) {
            Spacer(modifier = Modifier.height(8.dp))
            SettingsItemAction(
                imageVector = Icons.Outlined.Lock,
                text = stringResource(MR.string.vault_settings_open),
                onClick = { onAction(VaultSettingsUiAction.OpenVaultClicked) },
            )
            SettingsItemAction(
                imageVector = Icons.Outlined.Visibility,
                text = stringResource(MR.string.vault_settings_show_icon),
                onClick = { onAction(VaultSettingsUiAction.SetIconVisible(!uiState.iconVisible)) },
                trailingContent = {
                    Switch(
                        checked = uiState.iconVisible,
                        onCheckedChange = { onAction(VaultSettingsUiAction.SetIconVisible(it)) },
                    )
                },
            )
            SettingsItemAction(
                imageVector = Icons.Outlined.Fingerprint,
                text = stringResource(MR.string.vault_settings_biometrics),
                onClick = { onAction(VaultSettingsUiAction.SetBiometricsEnabled(!uiState.biometricsEnabled)) },
                trailingContent = {
                    Switch(
                        checked = uiState.biometricsEnabled,
                        onCheckedChange = { onAction(VaultSettingsUiAction.SetBiometricsEnabled(it)) },
                    )
                },
            )
            Spacer(modifier = Modifier
                .fillMaxWidth()
                .height(24.dp))
        }
    }
}
