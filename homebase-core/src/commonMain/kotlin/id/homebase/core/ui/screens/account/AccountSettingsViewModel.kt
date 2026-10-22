package id.homebase.core.ui.screens.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.storage.StorageQuota
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The plan & storage half of Settings > Account: the identity's plan and storage, and where to go about it,
 * the owner console's per-app usage to free up space and the hosting provider's upgrade page to get more.
 * Log out and delete account on the same screen stay with [id.homebase.core.ui.screens.settings.SettingsViewModel].
 */
class AccountSettingsViewModel(
    private val storageQuota: StorageQuota,
    private val credentialsManager: CredentialsManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AccountSettingsUiState())
    val uiState: StateFlow<AccountSettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val usage = storageQuota.refresh()
            _uiState.update {
                it.copy(usage = usage, isFull = storageQuota.state.value.isFull, isLoading = false)
            }
        }
    }

    fun onAction(action: AccountSettingsUiAction) {
        when (action) {
            AccountSettingsUiAction.SeeWhatEachAppStoresClicked -> openOwnerConsole("/owner/apps")
            AccountSettingsUiAction.HowToUpgradeClicked -> _uiState.update { it.copy(showUpgradeDialog = true) }
            AccountSettingsUiAction.UpgradeDialogDismissed -> _uiState.update { it.copy(showUpgradeDialog = false) }
            AccountSettingsUiAction.UpgradeContinueClicked -> _uiState.update { it.afterUpgradeContinue() }
        }
    }

    fun eventConsumed() {
        _uiState.update { it.copy(uiEvent = null) }
    }

    private fun openOwnerConsole(path: String) {
        viewModelScope.launch {
            val domain = credentialsManager.getActiveDomain() ?: return@launch
            _uiState.update { it.copy(uiEvent = AccountSettingsUiEvent.OpenUrl("https://${domain.domainName}$path")) }
        }
    }
}
