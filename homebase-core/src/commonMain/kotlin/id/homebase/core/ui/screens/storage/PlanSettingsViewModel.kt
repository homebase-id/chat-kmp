package id.homebase.core.ui.screens.storage

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
 * The identity's plan and storage, and where to go about it: the owner console's per-app usage to free up
 * space, and its plan page's upgrade instructions to get more.
 */
class PlanSettingsViewModel(
    private val storageQuota: StorageQuota,
    private val credentialsManager: CredentialsManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PlanSettingsUiState())
    val uiState: StateFlow<PlanSettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val usage = storageQuota.refresh()
            _uiState.update {
                it.copy(usage = usage, isFull = storageQuota.state.value.isFull, isLoading = false)
            }
        }
    }

    fun onAction(action: PlanSettingsUiAction) {
        when (action) {
            PlanSettingsUiAction.SeeWhatEachAppStoresClicked -> openOwnerConsole("/owner/apps")
            PlanSettingsUiAction.HowToUpgradeClicked -> openOwnerConsole("/owner/settings/plan?upgrade=1")
        }
    }

    fun eventConsumed() {
        _uiState.update { it.copy(uiEvent = null) }
    }

    private fun openOwnerConsole(path: String) {
        viewModelScope.launch {
            val domain = credentialsManager.getActiveDomain() ?: return@launch
            _uiState.update { it.copy(uiEvent = PlanSettingsUiEvent.OpenUrl("https://${domain.domainName}$path")) }
        }
    }
}
