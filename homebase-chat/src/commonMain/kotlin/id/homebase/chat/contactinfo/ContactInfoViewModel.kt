package id.homebase.chat.contactinfo

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.navigation.toRoute
import id.homebase.core.ui.navigation.Route
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class ContactInfoViewModel(
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    val contactInfo = savedStateHandle.toRoute<Route.ContactInfo>()
    private val _uiState = MutableStateFlow(ContactInfoUiState(text = contactInfo.odinId))
    val uiState: StateFlow<ContactInfoUiState> = _uiState.asStateFlow()

    fun onUiAction(action: ContactInfoUiAction) {
        when (action) {
            is ContactInfoUiAction.BackClicked -> _uiState.update { it.copy(uiEvent = ContactInfoUiEvent.Back)}
        }
    }

    fun eventConsumed() {
        _uiState.update { it.copy(uiEvent = null) }
    }
}