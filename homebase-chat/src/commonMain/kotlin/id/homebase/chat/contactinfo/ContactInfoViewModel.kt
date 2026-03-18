package id.homebase.chat.contactinfo

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import co.touchlab.kermit.Logger
import id.homebase.api.common.OdinId
import id.homebase.chat.services.convo.contact.ContactService
import id.homebase.core.ui.navigation.Route
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ContactInfoViewModel(
    savedStateHandle: SavedStateHandle,
    val contactService: ContactService,
) : ViewModel() {
    val route = savedStateHandle.toRoute<Route.ContactInfo>()
    private val _uiState = MutableStateFlow(ContactInfoUiState())
    val uiState: StateFlow<ContactInfoUiState> = _uiState.asStateFlow()

    init {
        loadData()
    }

    fun onUiAction(action: ContactInfoUiAction) {
        when (action) {
            is ContactInfoUiAction.BackClicked -> _uiState.update { it.copy(uiEvent = ContactInfoUiEvent.Back)}
        }
    }

    fun eventConsumed() {
        _uiState.update { it.copy(uiEvent = null) }
    }

    private fun loadData() {
        viewModelScope.launch {
            try {
                contactService.start()
                val contact = contactService.resolveByOdinId(OdinId(route.odinId))
                _uiState.update { it.copy(contact = contact, isLoading = false) }
            } catch (e: Exception) {
                Logger.e( "Failed to load contact", e)
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }
}