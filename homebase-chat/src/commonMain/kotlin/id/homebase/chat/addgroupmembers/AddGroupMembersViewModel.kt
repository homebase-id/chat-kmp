package id.homebase.chat.addgroupmembers

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.navigation.toRoute
import id.homebase.chat.services.convo.ContactService
import id.homebase.chat.services.convo.ConversationService
import id.homebase.core.ui.navigation.Route
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class AddGroupMembersViewModel(
    savedStateHandle: SavedStateHandle,
    private val contactService: ContactService,
    val conversationService: ConversationService,
) : ViewModel() {
    val route = savedStateHandle.toRoute<Route.GroupAddMembers>()
    private val _uiState = MutableStateFlow(AddGroupMembersUiState())
    val uiState: StateFlow<AddGroupMembersUiState> = _uiState.asStateFlow()

   fun onUiAction(action: AddGroupMembersUiAction) {
        when (action) {
            is AddGroupMembersUiAction.BackClicked -> {
                _uiState.update { it.copy(uiEvent = AddGroupMembersUiEvent.Back) }
            }
            is AddGroupMembersUiAction.ShowDialog -> {
                _uiState.update { it.copy(uiDialog = AddGroupMembersUiDialog.TestDialog) }
            }
        }
    }

    fun eventConsumed() {
        _uiState.update { it.copy(uiEvent = null) }
    }

    fun dialogConsumed() {
        _uiState.update { it.copy(uiDialog = null) }
    }
}

@Immutable
data class AddGroupMembersUiState(
    val isLoading: Boolean = false,
    val uiEvent: AddGroupMembersUiEvent? = null,
    val uiDialog: AddGroupMembersUiDialog? = null
)

sealed interface AddGroupMembersUiEvent {
    data object Back : AddGroupMembersUiEvent
    data class Error(val errorMessage: String) : AddGroupMembersUiEvent
}

sealed interface AddGroupMembersUiDialog {
    data object TestDialog : AddGroupMembersUiDialog
}

sealed interface AddGroupMembersUiAction {
    data object BackClicked : AddGroupMembersUiAction
    data object ShowDialog : AddGroupMembersUiAction
}
