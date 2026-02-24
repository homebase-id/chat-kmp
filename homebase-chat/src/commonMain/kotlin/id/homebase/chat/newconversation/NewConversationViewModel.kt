package id.homebase.chat.newconversation

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.homebase.chat.services.convo.ContactService
import id.homebase.chat.services.convo.ConversationService
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class NewConversationViewModel(
    private val contactService: ContactService,
    private val conversationWriterService: ConversationService,
) : ViewModel() {
    private val _uiState = MutableStateFlow(NewConversationUiState())
    val uiState: StateFlow<NewConversationUiState> = _uiState.asStateFlow()
    val searchTextState = TextFieldState()

    init {
        viewModelScope.launch {
            contactService.start()
            contactService.contacts.collect { contacts ->
                _uiState.value = _uiState.value.copy(
                    contacts = contacts.toPersistentList()
                )
                updateListContent()
            }
        }

        // Listen for search query changes
        viewModelScope.launch {
            snapshotFlow { searchTextState.text.toString() }.collectLatest {
                updateListContent()
            }
        }
    }

    fun onUiAction(action: NewConversationUiAction) {
        when (action) {
            is NewConversationUiAction.BackClicked -> _uiState.update { it.copy(uiEvent = NewConversationUiEvent.Back) }
            is NewConversationUiAction.CreateNewGroup -> _uiState.update { it.copy(uiEvent = NewConversationUiEvent.ShowCreateGroupScreen) }
            is NewConversationUiAction.CreateConversation -> {
                viewModelScope.launch {
                    try {
                        val conversationId = conversationWriterService.createConversation(
                            recipients = listOf(action.odinId),
                            title = "",
                            payloadBundle = null,
                        )
                        _uiState.update {
                            it.copy(
                                uiEvent = NewConversationUiEvent.LoadConversation(
                                    conversationId
                                )
                            )
                        }
                    } catch (e: Exception) {
                        sendEvent(
                            NewConversationUiEvent.ShowErrorMessage(
                                "Failed to create conversation: ${e.message}"
                            )
                        )
                    }
                }
            }
        }
    }

    fun eventConsumed() {
        _uiState.update { it.copy(uiEvent = null) }
    }

    private fun sendEvent(event: NewConversationUiEvent) {
        _uiState.update { it.copy(uiEvent = event) }
    }

    private fun updateListContent() {
        viewModelScope.launch {
            try {
                val searchQuery = searchTextState.text.toString()
                val result = mutableListOf<NewConversationListItem>()

                // Only display new group option if not showing search results
                if (searchQuery.isEmpty()) {
                    result.add(NewConversationListItem.NewGroup)
                }

                val contacts = if (searchQuery.isEmpty()) {
                    uiState.value.contacts
                } else {
                    uiState.value.contacts.filter {
                        it.name.contains(
                            searchQuery,
                            ignoreCase = true
                        )
                    }
                }

                val contactGroups = contacts.groupBy {
                    it.name.first().uppercase()
                }.map { (initial, contacts) ->
                    ContactGroup(
                        initial = initial,
                        contacts = contacts
                    )
                }.sortedBy { it.initial }
                result.add(NewConversationListItem.Contacts(contactGroups))

                _uiState.update { it.copy(items = result.toPersistentList()) }
            } catch (e: Exception) {
                sendEvent(
                    NewConversationUiEvent.ShowErrorMessage(
                        "Failed to load contacts: ${e.message}"
                    )
                )
            }
        }
    }
}