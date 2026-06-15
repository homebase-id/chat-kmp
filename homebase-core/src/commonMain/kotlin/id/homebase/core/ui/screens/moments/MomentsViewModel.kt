package id.homebase.core.ui.screens.moments

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.homebase.chat.conversationlist.ExtendPermissionUiState
import id.homebase.chat.conversationlist.ExtendPermissionViewModel
import id.homebase.core.config.momentsLabeledDrive
import id.homebase.core.moments.MomentsPreferences
import id.homebase.core.sync.OptionalDriveActivation
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class MomentsViewModel(
    private val momentsPreferences: MomentsPreferences,
    private val momentsPermissionViewModel: ExtendPermissionViewModel,
    private val optionalDriveActivation: OptionalDriveActivation,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MomentsUiState())
    val uiState: StateFlow<MomentsUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<MomentsUiEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<MomentsUiEvent> = _events.asSharedFlow()

    val momentsExtendPermissionViewModel: ExtendPermissionViewModel
        get() = momentsPermissionViewModel

    /**
     * Whether Moments is activated, derived from the mounted-drive state — the
     * cross-device source of truth. A registered drive is mounted by
     * AuthConnectionCoordinator's login pre-mount loop (so this is true on every device
     * the user has logged into), and first-time activation flips it true once the mount
     * lands. Replaces the former device-local `activated` preference flag, which could
     * disagree with the registry across devices.
     */
    val isActivated: StateFlow<Boolean> =
        optionalDriveActivation.isActivatedFlow(momentsLabeledDrive)
            .stateIn(
                viewModelScope,
                SharingStarted.Eagerly,
                optionalDriveActivation.isActivated(momentsLabeledDrive),
            )

    // Synchronous one-shot guard for the auto-activate collector below. isActivated only flips
    // true after activate()'s mount lands and the derived flow catches up, so a second grant
    // emission arriving in that window would otherwise re-enter; this latches before the suspend.
    private var activationKicked = false

    init {
        viewModelScope.launch {
            momentsPermissionViewModel.permissionsGranted
                .filter { it }
                .collect {
                    if (!activationKicked && !isActivated.value) {
                        activationKicked = true
                        // Auto-activate as soon as the drive is authorized — including the
                        // passive launch-time autoCheck grant. mountDrive registers the drive
                        // (persist=true) and mounts it; the drive appearing in driveStatuses
                        // flips isActivated true. It does not move the user.
                        val initiatedByUser = _uiState.value.setupInitiated
                        optionalDriveActivation.activate(momentsLabeledDrive)
                        _uiState.update {
                            it.copy(isCheckingPermissions = false, setupInitiated = false)
                        }
                        // Only navigate when the user actively completed onboarding (tapped
                        // "Set up"). AppNavHost pops the onboarding screen and navigates to
                        // Route.Moments on this event, so firing it for a passive launch-time
                        // grant would yank the user off the default ChatList tab.
                        if (initiatedByUser) {
                            _events.tryEmit(MomentsUiEvent.Activated)
                        }
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
