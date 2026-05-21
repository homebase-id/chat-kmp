package id.homebase.core.ui.screens.moments

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import id.homebase.core.moments.services.MomentGroupService
import id.homebase.core.moments.services.MomentsRecipient
import id.homebase.core.moments.services.MomentsRecipientLookupService
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val TAG = "CreateMomentGroupViewModel"

class CreateMomentGroupViewModel(
    private val recipientLookup: MomentsRecipientLookupService,
    private val groupService: MomentGroupService,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CreateMomentGroupUiState())
    val uiState: StateFlow<CreateMomentGroupUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<CreateMomentGroupUiEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<CreateMomentGroupUiEvent> = _events.asSharedFlow()

    init {
        viewModelScope.launch {
            recipientLookup.recipients.collect { snapshot ->
                // Recent + contacts entries that are individuals (groups can't
                // be members of groups). Recent is included so the picker is
                // useful immediately; the lookup snapshot already excludes
                // self.
                val individuals: List<MomentsRecipient.Individual> =
                    (snapshot.recent + snapshot.contacts)
                        .filterIsInstance<MomentsRecipient.Individual>()
                        .distinctBy { it.odinId }
                _uiState.update { it.copy(contacts = individuals) }
            }
        }
    }

    fun onAction(action: CreateMomentGroupUiAction) {
        when (action) {
            is CreateMomentGroupUiAction.TitleChanged ->
                _uiState.update { it.copy(title = action.text) }

            is CreateMomentGroupUiAction.QueryChanged ->
                _uiState.update { it.copy(query = action.text) }

            is CreateMomentGroupUiAction.ToggleMember ->
                _uiState.update {
                    val next = if (action.odinId in it.selected) it.selected - action.odinId
                    else it.selected + action.odinId
                    it.copy(selected = next)
                }

            CreateMomentGroupUiAction.CreateClicked -> create()
        }
    }

    private fun create() {
        val state = _uiState.value
        if (!state.canCreate) return

        _uiState.update { it.copy(isCreating = true) }

        viewModelScope.launch {
            try {
                groupService.createGroup(
                    title = state.title.trim(),
                    members = state.selected.toList(),
                )
                _events.tryEmit(CreateMomentGroupUiEvent.Created)
            } catch (t: Throwable) {
                Logger.e(throwable = t, tag = TAG) { "createGroup failed: ${t.message}" }
                _events.tryEmit(CreateMomentGroupUiEvent.CreateFailed(t.message))
            } finally {
                _uiState.update { it.copy(isCreating = false) }
            }
        }
    }
}
