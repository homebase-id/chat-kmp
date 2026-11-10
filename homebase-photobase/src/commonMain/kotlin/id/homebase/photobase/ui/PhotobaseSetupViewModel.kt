package id.homebase.photobase.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.homebase.chat.conversationlist.ExtendPermissionUiState
import id.homebase.chat.conversationlist.ExtendPermissionViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** [installed]: null until the server has answered. */
data class PhotobaseSetupUiState(
    val installed: Boolean? = null,
    val setupInitiated: Boolean = false,
    val checkFailed: Boolean = false,
)

sealed interface PhotobaseSetupUiAction {
    data object SetupClicked : PhotobaseSetupUiAction
    data object Paused : PhotobaseSetupUiAction
    data object Resumed : PhotobaseSetupUiAction
    data object SetupLeft : PhotobaseSetupUiAction
    data object RetryCheck : PhotobaseSetupUiAction
}

/** Installing Photobase is what creates the Photos drive; the owner console does it, in a browser. */
class PhotobaseSetupViewModel(
    val extendPermissionViewModel: ExtendPermissionViewModel,
    private val onNotInstalled: () -> Unit = {},
) : ViewModel() {
    private val _uiState = MutableStateFlow(PhotobaseSetupUiState())
    val uiState: StateFlow<PhotobaseSetupUiState> = _uiState.asStateFlow()

    private var setupPaused = false

    init {
        addCloseable(extendPermissionViewModel)
        viewModelScope.launch {
            combine(
                extendPermissionViewModel.permissionsChecked,
                extendPermissionViewModel.permissionsGranted,
            ) { checked, granted -> if (granted) true else if (checked) false else null }
                .collect { installed ->
                    if (installed == false) onNotInstalled()
                    _uiState.update {
                        it.copy(installed = installed, setupInitiated = if (installed == true) false else it.setupInitiated)
                    }
                }
        }
        viewModelScope.launch {
            extendPermissionViewModel.checkFailed.collect { failed -> _uiState.update { it.copy(checkFailed = failed) } }
        }
        viewModelScope.launch {
            // Cancelled in the owner console while away: coming back must not ask again.
            extendPermissionViewModel.navigateAwayRequest.collect { setupPaused = false }
        }
        viewModelScope.launch {
            extendPermissionViewModel.uiState
                .filter { it is ExtendPermissionUiState.Dismissed }
                .collect { _uiState.update { state -> state.copy(setupInitiated = false) } }
        }
    }

    fun onAction(action: PhotobaseSetupUiAction) {
        when (action) {
            PhotobaseSetupUiAction.SetupClicked -> {
                _uiState.update { it.copy(setupInitiated = true) }
                extendPermissionViewModel.recheckPermissions()
            }
            // Leaving Chat makes setup's verdict stale: recheck on return.
            PhotobaseSetupUiAction.Paused -> if (_uiState.value.setupInitiated) {
                setupPaused = true
                extendPermissionViewModel.dismissDialog()
            }
            PhotobaseSetupUiAction.Resumed -> if (setupPaused) {
                setupPaused = false
                onAction(PhotobaseSetupUiAction.SetupClicked)
            }
            PhotobaseSetupUiAction.SetupLeft -> setupPaused = false
            PhotobaseSetupUiAction.RetryCheck -> extendPermissionViewModel.recheckPermissions()
        }
    }
}
