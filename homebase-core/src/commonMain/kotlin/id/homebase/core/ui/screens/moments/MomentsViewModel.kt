package id.homebase.core.ui.screens.moments

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.homebase.chat.conversationlist.ExtendPermissionUiState
import id.homebase.chat.conversationlist.ExtendPermissionViewModel
import id.homebase.core.auth.AuthConnectionCoordinator
import id.homebase.core.config.momentsLabeledDrive
import id.homebase.core.moments.MomentsPreferences
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class MomentsViewModel(
    private val momentsPreferences: MomentsPreferences,
    private val momentsPermissionViewModel: ExtendPermissionViewModel,
    private val authConnectionCoordinator: AuthConnectionCoordinator,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MomentsUiState())
    val uiState: StateFlow<MomentsUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<MomentsUiEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<MomentsUiEvent> = _events.asSharedFlow()

    val momentsExtendPermissionViewModel: ExtendPermissionViewModel
        get() = momentsPermissionViewModel

    init {
        viewModelScope.launch {
            momentsPermissionViewModel.permissionsGranted
                .filter { it }
                .collect {
                    if (!momentsPreferences.activated.value) {
                        momentsPreferences.setActivated(true)
                        authConnectionCoordinator.mountDrive(momentsLabeledDrive)
                        _uiState.update {
                            it.copy(isCheckingPermissions = false, setupInitiated = false)
                        }
                        _events.tryEmit(MomentsUiEvent.Activated)
                    }
                }
        }

        // Resetting setupInitiated on every Dismissed transition (Cancel button, outside-tap,
        // or owner-console cancellation) ensures the next visit to onboarding starts clean —
        // so the lifecycle ON_RESUME re-check no longer re-prompts a user who already said no.
        viewModelScope.launch {
            momentsPermissionViewModel.uiState
                .filter { it is ExtendPermissionUiState.Dismissed }
                .collect {
                    _uiState.update {
                        it.copy(isCheckingPermissions = false, setupInitiated = false)
                    }
                }
        }
    }

    fun onAction(action: MomentsUiAction) {
        when (action) {
            MomentsUiAction.SetupClicked -> {
                _uiState.update {
                    it.copy(isCheckingPermissions = true, setupInitiated = true)
                }
                momentsPermissionViewModel.recheckPermissions()
            }

            MomentsUiAction.DismissOnboardingClicked -> {
                viewModelScope.launch {
                    momentsPreferences.setIconVisible(false)
                    _events.tryEmit(MomentsUiEvent.CloseOnboarding)
                }
            }
        }
    }
}
