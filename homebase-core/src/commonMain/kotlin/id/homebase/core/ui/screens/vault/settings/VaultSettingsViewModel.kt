package id.homebase.core.ui.screens.vault.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.homebase.core.vault.VaultPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class VaultSettingsViewModel(
    private val vaultPreferences: VaultPreferences,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        VaultSettingsUiState(
            iconVisible = vaultPreferences.iconVisible.value,
            biometricsEnabled = vaultPreferences.biometricsEnabled.value,
        )
    )
    val uiState: StateFlow<VaultSettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            vaultPreferences.iconVisible.collect { v ->
                _uiState.update { it.copy(iconVisible = v) }
            }
        }
        viewModelScope.launch {
            vaultPreferences.biometricsEnabled.collect { v ->
                _uiState.update { it.copy(biometricsEnabled = v) }
            }
        }
    }

    fun onAction(action: VaultSettingsUiAction) {
        when (action) {
            VaultSettingsUiAction.OpenVaultClicked -> {
                // Handled by the screen — it dispatches to VaultViewModel.onOpenVault.
            }
            is VaultSettingsUiAction.SetIconVisible -> {
                viewModelScope.launch { vaultPreferences.setIconVisible(action.visible) }
            }
            is VaultSettingsUiAction.SetBiometricsEnabled -> {
                viewModelScope.launch { vaultPreferences.setBiometricsEnabled(action.enabled) }
            }
        }
    }
}
