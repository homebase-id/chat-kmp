package id.homebase.chat.createconversation

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.homebase.chat.data.ContactUiModel
import id.homebase.chat.services.ChatProtocol
import id.homebase.chat.services.convo.contact.ContactService
import id.homebase.chat.services.convo.ConversationService
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class CreateConversationViewModel(
    private val contactService: ContactService,
    private val conversationWriterService: ConversationService,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        CreateConversationUiState()
    )
    val uiState: StateFlow<CreateConversationUiState> = _uiState.asStateFlow()
    val searchTextState = TextFieldState()

    init {
        viewModelScope.launch {
            contactService.start()
            contactService.contacts
                .combine(snapshotFlow { searchTextState.text.toString() }) { contacts, query ->
                    filterAndGroup(contacts, query)
                }
                .catch {
                    sendEvent(
                        CreateConversationUiEvent.ShowErrorMessage(
                            it.message ?: "Unknown error"
                        )
                    )
                }
                .collectLatest { contactGroups ->
                    _uiState.update { it.copy(displayItems = contactGroups.toPersistentList()) }
                }
        }
    }

    fun onUiAction(action: CreateConversationUiAction) {
        when (action) {
            is CreateConversationUiAction.BackClicked -> _uiState.update { it.copy(uiEvent = CreateConversationUiEvent.Back) }
            is CreateConversationUiAction.CreateNewGroup -> _uiState.update { it.copy(uiEvent = CreateConversationUiEvent.ShowCreateGroupScreen) }
            is CreateConversationUiAction.CreateSelfConversation -> {
                _uiState.update {
                    it.copy(
                        uiEvent = CreateConversationUiEvent.LoadConversation(
                            ChatProtocol.ConversationWithYourselfId
                        )
                    )
                }
            }
            is CreateConversationUiAction.ContactClicked -> {
                viewModelScope.launch {
                    try {
                        val conversationId = conversationWriterService.createConversation(
                            recipients = listOf(action.contact.odinId),
                            title = "",
                            payloadBundle = null,
                        ).conversationId
                        _uiState.update {
                            it.copy(
                                uiEvent = CreateConversationUiEvent.LoadConversation(
                                    conversationId
                                )
                            )
                        }
                    } catch (e: Exception) {
                        sendEvent(CreateConversationUiEvent.ShowErrorMessage("Failed to create conversation: ${e.message}"))
                    }
                }
            }
        }
    }

    fun eventConsumed() {
        _uiState.update { it.copy(uiEvent = null) }
    }

    private fun sendEvent(event: CreateConversationUiEvent) {
        _uiState.update { it.copy(uiEvent = event) }
    }

    private fun filterAndGroup(
        contacts: List<ContactUiModel>,
        query: String
    ): List<CreateConversationListItem> {
        val result = mutableListOf<CreateConversationListItem>()

        // Only display new group and note to self options if not showing search results
        if (query.isEmpty()) {
            result.add(CreateConversationListItem.NoteToSelf)
            result.add(CreateConversationListItem.ConnectionRequest)
            result.add(CreateConversationListItem.NewGroup)
        }

        val contacts = if (query.isEmpty()) {
            contacts
        } else {
            contacts.filter {
                it.name.contains(
                    query,
                    ignoreCase = true
                )
            }
        }.distinctBy { it.odinId }
        val groups = contacts.groupBy {
            it.name.firstOrNull()?.uppercase() ?: "#"
        }.map { (initial, contacts) ->
            ContactGroup(
                initial = initial,
                contacts = contacts
            )
        }.sortedBy { it.initial }
        result.add(CreateConversationListItem.Contacts(groups))
        return result
    }
}