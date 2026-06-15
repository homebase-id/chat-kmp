package id.homebase.core.ui.screens.lists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.homebase.chat.conversationlist.ExtendPermissionUiState
import id.homebase.chat.conversationlist.ExtendPermissionViewModel
import id.homebase.core.auth.AuthConnectionCoordinator
import id.homebase.core.config.listsLabeledDrive
import id.homebase.core.lists.ListsPreferences
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ListsViewModel(
    private val listsPreferences: ListsPreferences,
    private val listsPermissionViewModel: ExtendPermissionViewModel,
    private val authConnectionCoordinator: AuthConnectionCoordinator,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ListsUiState())
    val uiState: StateFlow<ListsUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<ListsUiEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<ListsUiEvent> = _events.asSharedFlow()

    val listsExtendPermissionViewModel: ExtendPermissionViewModel
        get() = listsPermissionViewModel

    init {
        viewModelScope.launch {
            listsPermissionViewModel.permissionsGranted
                .filter { it }
                .collect {
                    if (!listsPreferences.activated.value) {
                        // Auto-activate as soon as the drive is authorized — including the
                        // passive launch-time autoCheck grant. Activation persists the flag
                        // and mounts the drive; it does not move the user.
                        val initiatedByUser = _uiState.value.setupInitiated
                        listsPreferences.setActivated(true)
                        authConnectionCoordinator.mountDrive(listsLabeledDrive)
                        _uiState.update {
                            it.copy(isCheckingPermissions = false, setupInitiated = false)
                        }
                        // Only navigate when the user actively completed onboarding (tapped
                        // "Set it up"). AppNavHost pops the onboarding screen and navigates to
                        // Route.Lists on this event; firing it for a passive launch-time grant
                        // would yank the user off the default ChatList tab.
                        if (initiatedByUser) {
                            _events.tryEmit(ListsUiEvent.Activated)
                        }
                    }
                }
        }

        // Resetting setupInitiated on every Dismissed transition (Cancel button, outside-tap,
        // or owner-console cancellation) ensures the next visit to onboarding starts clean —
        // so the lifecycle ON_RESUME re-check no longer re-prompts a user who already said no.
        viewModelScope.launch {
            listsPermissionViewModel.uiState
                .filter { it is ExtendPermissionUiState.Dismissed }
                .collect {
                    _uiState.update {
                        it.copy(isCheckingPermissions = false, setupInitiated = false)
                    }
                }
        }
    }

    fun onAction(action: ListsUiAction) {
        when (action) {
            ListsUiAction.SetupClicked -> {
                _uiState.update {
                    it.copy(isCheckingPermissions = true, setupInitiated = true)
                }
                listsPermissionViewModel.recheckPermissions()
            }

            ListsUiAction.DismissOnboardingClicked -> {
                viewModelScope.launch {
                    listsPreferences.setIconVisible(false)
                    _events.tryEmit(ListsUiEvent.CloseOnboarding)
                }
            }
        }
    }
}
