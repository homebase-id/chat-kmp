package id.homebase.chat.addgroupmembers

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import co.touchlab.kermit.Logger
import id.homebase.api.common.OdinId
import id.homebase.chat.createconversation.ContactGroup
import id.homebase.chat.data.ContactUiModel
import id.homebase.chat.data.ConversationUiModel
import id.homebase.chat.selectmembers.filterAndGroup
import id.homebase.chat.services.convo.contact.ContactService
import id.homebase.chat.services.convo.ConversationService
import id.homebase.core.ui.navigation.Route
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

class AddGroupMembersViewModel(
    savedStateHandle: SavedStateHandle,
    private val contactService: ContactService,
    private val conversationService: ConversationService,
) : ViewModel() {
    val route = savedStateHandle.toRoute<Route.GroupAddMembers>()
    private val _uiState = MutableStateFlow(AddGroupMembersUiState())
    val uiState: StateFlow<AddGroupMembersUiState> = _uiState.asStateFlow()
    val searchTextState = TextFieldState()

    init {
        loadData()
        viewModelScope.launch {
            contactService.start()
            contactService.contacts
                .combine(snapshotFlow { searchTextState.text.toString() }) { contacts, query ->
                    contacts.filterAndGroup(query)
                }
                .catch {
                    sendEvent(AddGroupMembersUiEvent.Error(it.message ?: "Unknown error"))
                }
                .collectLatest { contactGroups ->
                    _uiState.update { it.copy(displayItems = contactGroups.toPersistentList()) }
                }
        }
    }

    fun onUiAction(action: AddGroupMembersUiAction) {
        when (action) {
            is AddGroupMembersUiAction.BackClicked -> {
                _uiState.update { it.copy(uiEvent = AddGroupMembersUiEvent.Back) }
            }

            is AddGroupMembersUiAction.ContactClicked -> {
                viewModelScope.launch {
                    val selected = uiState.value.selectedContacts.toMutableList()
                    if (selected.contains(action.contact)) {
                        selected.remove(action.contact)
                    } else {
                        selected.add(action.contact)
                    }
                    _uiState.update { it.copy(selectedContacts = selected.toPersistentList()) }
                }
            }

            is AddGroupMembersUiAction.Save -> {
                viewModelScope.launch {
                    try {
                        val conversation = uiState.value.conversation ?: return@launch
                        _uiState.update { it.copy(isLoading = true) }

                        val newRecipients = uiState.value.selectedContacts.map { it.odinId }
                        conversationService.updateGroupMembers(
                            conversationId = conversation.id,
                            add = newRecipients,
                            remove = emptyList()
                        )

                        sendEvent(AddGroupMembersUiEvent.Back)
                    } catch (e: Exception) {
                        sendEvent(AddGroupMembersUiEvent.Error("Failed to update conversation: ${e.message}"))
                    }
                }
            }
        }
    }

    fun eventConsumed() {
        _uiState.update { it.copy(uiEvent = null) }
    }

    private fun sendEvent(event: AddGroupMembersUiEvent) {
        _uiState.update { it.copy(uiEvent = event, isLoading = false) }
    }

    private fun loadData() {
        viewModelScope.launch {
            try {
                val conversation =
                    conversationService.getConversation(Uuid.parse(route.conversationId))
                if (conversation != null) {
                    _uiState.update {
                        it.copy(
                            originalMembers = conversation.participants,
                            conversation = conversation,
                            isLoading = false,
                        )
                    }
                } else {
                    Logger.d("Failed to load conversation")
                    _uiState.update { it.copy(isLoading = false) }
                }

            } catch (e: Exception) {
                Logger.e("Failed to load conversation", e)
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }
}

@Immutable
data class AddGroupMembersUiState(
    val isLoading: Boolean = true,
    val originalMembers: List<OdinId> = listOf(),
    val conversation: ConversationUiModel? = null,
    val displayItems: PersistentList<ContactGroup> = persistentListOf(),
    val selectedContacts: PersistentList<ContactUiModel> = persistentListOf(),
    val uiEvent: AddGroupMembersUiEvent? = null,
)

sealed interface AddGroupMembersUiEvent {
    data object Back : AddGroupMembersUiEvent
    data class Error(val errorMessage: String) : AddGroupMembersUiEvent
}

sealed interface AddGroupMembersUiAction {
    data object BackClicked : AddGroupMembersUiAction
    data class ContactClicked(val contact: ContactUiModel) : AddGroupMembersUiAction
    data object Save : AddGroupMembersUiAction

}
