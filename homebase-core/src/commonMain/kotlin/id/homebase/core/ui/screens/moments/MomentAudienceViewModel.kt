package id.homebase.core.ui.screens.moments

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import id.homebase.core.moments.services.MomentCreateFlowState
import id.homebase.core.moments.services.MomentsPostSenderService
import id.homebase.core.moments.services.MomentsRecipient
import id.homebase.core.moments.services.MomentsRecipientId
import id.homebase.core.moments.services.MomentsRecipientLookupService
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val TAG = "MomentAudienceViewModel"

class MomentAudienceViewModel(
    private val recipientLookup: MomentsRecipientLookupService,
    private val postSender: MomentsPostSenderService,
    private val flowState: MomentCreateFlowState,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        MomentAudienceUiState(draftReady = flowState.draft.value != null)
    )
    val uiState: StateFlow<MomentAudienceUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<MomentAudienceUiEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<MomentAudienceUiEvent> = _events.asSharedFlow()

    init {
        viewModelScope.launch {
            recipientLookup.recipients.collect { list ->
                _uiState.update { it.copy(recipients = list) }
            }
        }
        viewModelScope.launch {
            flowState.draft.collect { draft ->
                _uiState.update { it.copy(draftReady = draft != null) }
            }
        }
    }

    fun onAction(action: MomentAudienceUiAction) {
        when (action) {
            is MomentAudienceUiAction.QueryChanged ->
                _uiState.update { it.copy(query = action.text) }

            is MomentAudienceUiAction.ToggleRecipient ->
                _uiState.update {
                    val next = if (action.id in it.selected) it.selected - action.id
                    else it.selected + action.id
                    it.copy(selected = next)
                }

            MomentAudienceUiAction.PostClicked -> post()
        }
    }

    private fun post() {
        val state = _uiState.value
        val draft = flowState.draft.value
        if (!state.canPost || draft == null) return

        // `recipients` is a recent+others snapshot — flatten via `.all` for the
        // selection lookup, then resolve the picked ids back to recipient objects.
        val selectedRecipients: List<MomentsRecipient> =
            state.recipients.all.filter { it.id in state.selected }
        val odinIds = selectedRecipients.flatMap { it.odinIds }.distinct()

        _uiState.update { it.copy(isPosting = true) }

        viewModelScope.launch {
            try {
                postSender.postMoment(
                    attachments = draft.attachments,
                    description = draft.description,
                    recipients = odinIds,
                )
                // recordUsed is fire-and-forget on the lookup service's own
                // singleton scope — see its KDoc. Calling it from
                // viewModelScope used to cancel the in-flight MRU upload the
                // moment we navigated away from this screen.
                recipientLookup.recordUsed(selectedRecipients)
                flowState.clear()
                _events.tryEmit(MomentAudienceUiEvent.Posted)
            } catch (t: Throwable) {
                Logger.e(throwable = t, tag = TAG) { "postMoment failed: ${t.message}" }
                _events.tryEmit(MomentAudienceUiEvent.PostFailed(t.message))
            } finally {
                _uiState.update { it.copy(isPosting = false) }
            }
        }
    }
}
