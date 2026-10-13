package id.homebase.chat.calling.history

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.homebase.api.client.auth.OwnerSession
import id.homebase.api.common.OdinId
import id.homebase.chat.createconversation.CreateConversationListItem
import id.homebase.chat.createconversation.filterAndGroup
import id.homebase.chat.data.ContactUiModel
import id.homebase.chat.services.calling.CallSession
import id.homebase.chat.services.convo.contact.ContactConnectionState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class NewCallRow(val contact: ContactUiModel, val connected: Boolean)

data class NewCallGroup(val initial: String, val rows: List<NewCallRow>)

data class NewCallUiState(
    val groups: List<NewCallGroup> = emptyList(),
    val callable: Boolean = false,
)

sealed interface NewCallAction {
    data class Start(val contact: ContactUiModel, val video: Boolean) : NewCallAction
}

sealed interface NewCallEvent {
    data class StartCall(val peer: OdinId, val video: Boolean) : NewCallEvent
}

class NewCallViewModel(
    contacts: Flow<List<ContactUiModel>>,
    self: Flow<OwnerSession?>,
    session: CallSession,
    private val callsAvailable: Boolean,
) : ViewModel() {
    private val _uiState = MutableStateFlow(NewCallUiState())
    val uiState: StateFlow<NewCallUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<NewCallEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<NewCallEvent> = _events.asSharedFlow()

    val searchTextState = TextFieldState()

    init {
        viewModelScope.launch {
            combine(
                contacts,
                snapshotFlow { searchTextState.text.toString() },
                self,
                session.state.map { it.isActive }.distinctUntilChanged(),
            ) { contactList, query, owner, active ->
                val groups = filterAndGroup(contactList, emptyList(), query, owner)
                    .filterIsInstance<CreateConversationListItem.Contacts>()
                    .flatMap { it.contactGroups }
                    .map { group ->
                        NewCallGroup(
                            group.initial,
                            group.contacts.map { NewCallRow(it, it.connectionState == ContactConnectionState.Connected) },
                        )
                    }
                NewCallUiState(groups = groups, callable = callsAvailable && !active)
            }.collect { state -> _uiState.update { state } }
        }
    }

    fun onAction(action: NewCallAction) {
        when (action) {
            is NewCallAction.Start -> {
                val connected = action.contact.connectionState == ContactConnectionState.Connected
                if (connected && _uiState.value.callable) {
                    _events.tryEmit(NewCallEvent.StartCall(action.contact.odinId, action.video))
                }
            }
        }
    }
}
