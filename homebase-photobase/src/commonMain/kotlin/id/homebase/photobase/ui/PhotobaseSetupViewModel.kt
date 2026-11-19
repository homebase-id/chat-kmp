package id.homebase.photobase.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.homebase.api.image.MediaQuality
import id.homebase.api.image.SendQuality
import id.homebase.chat.conversationlist.ExtendPermissionUiState
import id.homebase.chat.conversationlist.ExtendPermissionViewModel
import id.homebase.photobase.backup.BackupSettings
import id.homebase.photobase.backup.PhotoBackupController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class PhotobaseSetupStep { Checking, Onboarding, Finishing, ConfirmQuality, Installed }

/** [installed]: null until the server has answered. */
data class PhotobaseSetupUiState(
    val installed: Boolean? = null,
    val setupInitiated: Boolean = false,
    val checkFailed: Boolean = false,
    /** Away in the owner console, or back and waiting on the recheck: the last verdict is stale. */
    val finishing: Boolean = false,
    val qualityUnconfirmed: Boolean = false,
    val quality: SendQuality = BackupSettings().let { SendQuality(it.photoQuality, it.videoQuality) },
) {
    val step: PhotobaseSetupStep
        get() = when (installed) {
            null -> PhotobaseSetupStep.Checking
            false -> if (finishing) PhotobaseSetupStep.Finishing else PhotobaseSetupStep.Onboarding
            true -> if (qualityUnconfirmed) PhotobaseSetupStep.ConfirmQuality else PhotobaseSetupStep.Installed
        }
}

sealed interface PhotobaseSetupUiAction {
    data object SetupClicked : PhotobaseSetupUiAction
    data object Paused : PhotobaseSetupUiAction
    data object Resumed : PhotobaseSetupUiAction
    data object SetupLeft : PhotobaseSetupUiAction
    data object RetryCheck : PhotobaseSetupUiAction
    data object OpenedOwnerConsole : PhotobaseSetupUiAction
    data object FinishingCancelled : PhotobaseSetupUiAction
    data class SetPhotoQuality(val quality: MediaQuality) : PhotobaseSetupUiAction
    data class SetVideoQuality(val quality: MediaQuality) : PhotobaseSetupUiAction
    data object QualityConfirmed : PhotobaseSetupUiAction
}

/**
 * Installing Photobase is what creates the Photos drive; the owner console does it, in a browser.
 * [backup] is null where this device can't back up, and then there are no qualities to confirm.
 */
class PhotobaseSetupViewModel(
    val extendPermissionViewModel: ExtendPermissionViewModel,
    private val onNotInstalled: () -> Unit = {},
    private val backup: PhotoBackupController? = null,
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
                    if (installed == true) setupPaused = false
                    if (installed == false) {
                        onNotInstalled()
                        backup?.let { controller ->
                            if (!controller.settings.value.qualityUnconfirmed) controller.update { it.copy(qualityUnconfirmed = true) }
                        }
                    }
                    _uiState.update {
                        it.copy(
                            installed = installed,
                            setupInitiated = if (installed == true) false else it.setupInitiated,
                            finishing = if (installed == true) false else it.finishing,
                        )
                    }
                }
        }
        backup?.let { controller ->
            viewModelScope.launch {
                controller.settings.collect { settings ->
                    _uiState.update {
                        it.copy(
                            qualityUnconfirmed = settings.qualityUnconfirmed,
                            quality = SendQuality(settings.photoQuality, settings.videoQuality),
                        )
                    }
                }
            }
        }
        viewModelScope.launch {
            extendPermissionViewModel.checkFailed.collect { failed ->
                _uiState.update { it.copy(checkFailed = failed, finishing = it.finishing && !failed) }
            }
        }
        viewModelScope.launch {
            // Cancelled in the owner console while away: coming back must not ask again.
            extendPermissionViewModel.navigateAwayRequest.collect {
                setupPaused = false
                _uiState.update { it.copy(finishing = false) }
            }
        }
        viewModelScope.launch {
            extendPermissionViewModel.uiState
                .filter { it is ExtendPermissionUiState.Dismissed }
                .collect { _uiState.update { state -> state.copy(setupInitiated = false) } }
        }
        viewModelScope.launch {
            // Still missing after the return: back to onboarding, where the dialog asks again.
            extendPermissionViewModel.uiState
                .filter { it is ExtendPermissionUiState.ShowDialog }
                .collect { _uiState.update { state -> state.copy(finishing = false) } }
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
            PhotobaseSetupUiAction.SetupLeft -> {
                setupPaused = false
                _uiState.update { it.copy(finishing = false) }
            }
            // Marked at the click, not ON_PAUSE: the dialog's own dismiss can clear setupInitiated first.
            PhotobaseSetupUiAction.OpenedOwnerConsole -> {
                setupPaused = true
                _uiState.update { it.copy(finishing = true) }
            }
            // Drops the in-flight recheck too, so its dialog can't pop up over onboarding.
            PhotobaseSetupUiAction.FinishingCancelled -> {
                setupPaused = false
                extendPermissionViewModel.dismissDialog()
                _uiState.update { it.copy(finishing = false) }
            }
            PhotobaseSetupUiAction.RetryCheck -> extendPermissionViewModel.recheckPermissions()
            is PhotobaseSetupUiAction.SetPhotoQuality -> backup?.update { it.copy(photoQuality = action.quality) }
            is PhotobaseSetupUiAction.SetVideoQuality -> backup?.update { it.copy(videoQuality = action.quality) }
            PhotobaseSetupUiAction.QualityConfirmed -> backup?.update { it.copy(qualityUnconfirmed = false) }
        }
    }
}
