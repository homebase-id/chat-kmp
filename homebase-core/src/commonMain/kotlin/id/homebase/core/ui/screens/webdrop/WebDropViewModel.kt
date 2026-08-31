@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.core.ui.screens.webdrop

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import id.homebase.chat.conversationlist.ExtendPermissionUiState
import id.homebase.chat.conversationlist.ExtendPermissionViewModel
import id.homebase.core.config.webDropLabeledDrive
import id.homebase.core.sync.OptionalDriveActivation
import id.homebase.core.ui.screens.webdrop.model.WebDropTtlChoice
import id.homebase.core.webdrop.WebDropIntroContent
import id.homebase.core.webdrop.WebDropProtocol
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val TAG = "WebDropViewModel"

class WebDropViewModel(
    private val webDropService: WebDropService,
    private val webDropStream: WebDropStream,
    private val webDropPermissionViewModel: ExtendPermissionViewModel,
    private val optionalDriveActivation: OptionalDriveActivation,
) : ViewModel() {

    private val _uiState = MutableStateFlow(WebDropUiState())
    val uiState: StateFlow<WebDropUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<WebDropUiEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<WebDropUiEvent> = _events.asSharedFlow()

    /** The dialog host; the onboarding screen renders this VM's dialog. */
    val extendPermissionViewModel: ExtendPermissionViewModel
        get() = webDropPermissionViewModel

    init {
        viewModelScope.launch {
            optionalDriveActivation.isActivatedFlow(webDropLabeledDrive).collect { activated ->
                _uiState.update { it.copy(driveActivated = activated) }
            }
        }

        // The owner approves the drive in a browser; mount when the grant lands rather than
        // making the user tap again.
        viewModelScope.launch {
            webDropPermissionViewModel.permissionsGranted.filter { it }.collect {
                if (_uiState.value.driveActivated != true) {
                    optionalDriveActivation.activate(webDropLabeledDrive)
                }
                _uiState.update { it.copy(setupInitiated = false) }
            }
        }

        // Covers all three cancel paths of the dialog so navigating back does not re-prompt.
        viewModelScope.launch {
            webDropPermissionViewModel.uiState
                .filter { it is ExtendPermissionUiState.Dismissed }
                .collect { _uiState.update { s -> s.copy(setupInitiated = false) } }
        }

        viewModelScope.launch {
            webDropStream.drops.collect { drops ->
                _uiState.update { it.copy(drops = drops) }
            }
        }
        viewModelScope.launch {
            webDropStream.isLoaded.collect { loaded ->
                _uiState.update { it.copy(isLoaded = loaded) }
            }
        }

        webDropStream.start()
    }

    fun onAction(action: WebDropUiAction) {
        when (action) {
            WebDropUiAction.OpenCompose ->
                _uiState.update { it.copy(composeOpen = true, createdUrl = null, error = null) }

            WebDropUiAction.SetupClicked -> {
                _uiState.update { it.copy(setupInitiated = true) }
                webDropPermissionViewModel.recheckPermissions()
            }

            WebDropUiAction.DismissOnboardingClicked ->
                _events.tryEmit(WebDropUiEvent.CloseOnboarding)

            is WebDropUiAction.FilesPicked -> _uiState.update { state ->
                val merged = (state.pickedFiles + action.files).distinctBy { it.path }
                if (merged.size > WebDropProtocol.MaxFilesPerDrop) {
                    state.copy(error = WebDropError.TooManyFiles)
                } else {
                    state.copy(pickedFiles = merged, error = null)
                }
            }

            is WebDropUiAction.RemovePickedFile -> _uiState.update { state ->
                state.copy(pickedFiles = state.pickedFiles.filterNot { it.path == action.path })
            }

            is WebDropUiAction.TtlChosen ->
                _uiState.update { it.copy(ttlChoice = action.choice) }

            WebDropUiAction.ToggleIntroSection ->
                _uiState.update { it.copy(introExpanded = !it.introExpanded) }

            is WebDropUiAction.RecipientNameChanged ->
                _uiState.update { it.copy(recipientName = action.name) }

            is WebDropUiAction.ConditionToggled -> _uiState.update { state ->
                val next = if (action.id in state.conditions) state.conditions - action.id
                else state.conditions + action.id
                state.copy(conditions = next)
            }

            is WebDropUiAction.ThemeChosen ->
                _uiState.update { it.copy(theme = action.theme) }

            WebDropUiAction.CreateClicked -> createDrop()

            WebDropUiAction.ComposeDismissed -> _uiState.update {
                // The theme survives on purpose; a typed name never does.
                it.copy(
                    composeOpen = false, pickedFiles = emptyList(), createdUrl = null,
                    isCreating = false, error = null,
                    introExpanded = false, recipientName = "", conditions = emptySet(),
                )
            }

            is WebDropUiAction.CopyLinkClicked ->
                _events.tryEmit(WebDropUiEvent.CopyLink(action.url))

            is WebDropUiAction.ShareClicked ->
                _events.tryEmit(WebDropUiEvent.ShareLink(action.url))

            is WebDropUiAction.RevokeClicked -> revoke(action.dropId)

            is WebDropUiAction.ClearClicked -> clear(action.receiptFileId)
        }
    }

    private fun createDrop() {
        val state = _uiState.value
        if (state.pickedFiles.isEmpty() || state.isCreating) return
        _uiState.update { it.copy(isCreating = true, error = null) }

        val intro = WebDropIntroContent(
            recipientName = state.recipientName.takeUnless { it.isBlank() },
            conditions = state.conditions.sorted(),
        ).takeUnless { it.isEmpty() }

        viewModelScope.launch {
            webDropService.createDrop(state.pickedFiles, state.ttlChoice, intro, state.theme)
                .onSuccess { created ->
                    _uiState.update { it.copy(isCreating = false, createdUrl = created.url) }
                    webDropStream.loadAll()
                    _events.tryEmit(WebDropUiEvent.ShareLink(created.url))
                }
                .onFailure {
                    _uiState.update { it.copy(isCreating = false, error = WebDropError.CreateFailed) }
                }
        }
    }

    private fun revoke(dropId: Uuid) {
        val row = _uiState.value.drops.firstOrNull { it.dropId == dropId } ?: return
        val dropFileId = row.dropFileId ?: return
        viewModelScope.launch {
            if (webDropService.revoke(dropFileId)) {
                webDropStream.removeOptimistic(dropId)
            } else {
                Logger.e(TAG) { "revoke failed for drop ${row.dropId}" }
            }
        }
    }

    private fun clear(receiptFileId: Uuid) {
        viewModelScope.launch {
            if (webDropService.clear(receiptFileId)) {
                webDropStream.clearOptimistic(receiptFileId)
            }
        }
    }

}
