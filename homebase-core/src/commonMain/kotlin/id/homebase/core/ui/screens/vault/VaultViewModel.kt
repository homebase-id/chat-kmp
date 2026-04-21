package id.homebase.core.ui.screens.vault

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.homebase.chat.conversationlist.ExtendPermissionViewModel
import id.homebase.core.vault.VaultPreferences
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class VaultViewModel(
    private val vaultPreferences: VaultPreferences,
    private val vaultPermissionViewModel: ExtendPermissionViewModel,
) : ViewModel() {

    private val _uiState = MutableStateFlow(VaultUiState())
    val uiState: StateFlow<VaultUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<VaultUiEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<VaultUiEvent> = _events.asSharedFlow()

    val vaultExtendPermissionViewModel: ExtendPermissionViewModel
        get() = vaultPermissionViewModel

    init {
        viewModelScope.launch {
            vaultPermissionViewModel.permissionsGranted
                .filter { it }
                .collect {
                    if (!vaultPreferences.activated.value) {
                        vaultPreferences.setActivated(true)
                        _uiState.update { it.copy(isCheckingPermissions = false) }
                        _events.tryEmit(VaultUiEvent.Activated)
                    }
                }
        }
    }

    fun onAction(action: VaultUiAction) {
        when (action) {
            VaultUiAction.SetupClicked -> {
                _uiState.update { it.copy(isCheckingPermissions = true) }
                vaultPermissionViewModel.recheckPermissions()
            }
            VaultUiAction.DismissOnboardingClicked -> {
                viewModelScope.launch {
                    vaultPreferences.setIconVisible(false)
                    _events.tryEmit(VaultUiEvent.CloseOnboarding)
                }
            }
        }
    }
}
