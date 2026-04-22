package id.homebase.chat.selectmembers

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.homebase.chat.createconversation.ContactGroup
import id.homebase.chat.data.ContactUiModel
import id.homebase.chat.services.convo.contact.ContactService
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SelectMembersViewModel(
    private val contactService: ContactService,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SelectMembersUiState())
    val uiState: StateFlow<SelectMembersUiState> = _uiState.asStateFlow()
    val searchTextState = TextFieldState()

    init {
        viewModelScope.launch {
            contactService.start()
            contactService.contacts
                .combine(snapshotFlow { searchTextState.text.toString() }) { contacts, query ->
                    contacts.filterAndGroup(query)
                }
                .catch {
                    sendEvent(SelectMembersUiEvent.ShowErrorMessage(it.message ?: "Unknown error"))
                }
                .collectLatest { contactGroups ->
                    _uiState.update { it.copy(displayItems = contactGroups.toPersistentList()) }
                }
        }
    }

    fun onUiAction(action: SelectMembersUiAction) {
        when (action) {
            is SelectMembersUiAction.BackClicked -> _uiState.update { it.copy(uiEvent = SelectMembersUiEvent.Back) }
            is SelectMembersUiAction.ContactClicked -> {
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

            is SelectMembersUiAction.NextClicked -> {
                _uiState.update {
                    it.copy(
                        uiEvent = SelectMembersUiEvent.MembersSelected(
                            uiState.value.selectedContacts.map { selected -> selected.id.toString() })
                    )
                }
            }
        }
    }

    fun eventConsumed() {
        _uiState.update { it.copy(uiEvent = null) }
    }

    private fun sendEvent(event: SelectMembersUiEvent) {
        _uiState.update { it.copy(uiEvent = event) }
    }
}

fun List<ContactUiModel>.filterAndGroup(query: String): List<ContactGroup> {
    val contacts = if (query.isEmpty()) {
        this
    } else {
        this.filter {
            it.name.contains(
                query,
                ignoreCase = true
            ) || it.odinId.toString().contains(
                query,
                ignoreCase = true
            )
        }
    }.distinctBy { it.odinId }
    return contacts.groupBy {
        it.name.firstOrNull()?.uppercase() ?: "#"
    }.map { (initial, contacts) ->
        ContactGroup(
            initial = initial,
            contacts = contacts
        )
    }.sortedBy { it.initial }
}