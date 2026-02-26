package id.homebase.chat.editconversationgroup

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.navigation.toRoute
import id.homebase.chat.services.convo.ConversationService
import id.homebase.core.ui.navigation.Route
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class EditConversationGroupViewModel(
    savedStateHandle: SavedStateHandle,
    val conversationService: ConversationService,
) : ViewModel() {
    val route = savedStateHandle.toRoute<Route.GroupEdit>()
    private val _uiState = MutableStateFlow(EditConversationGroupUiState())
    val uiState: StateFlow<EditConversationGroupUiState> = _uiState.asStateFlow()

    fun onUiAction(action: EditConversationGroupUiAction) {
        when (action) {
            is EditConversationGroupUiAction.BackClicked -> {
                _uiState.update { it.copy(uiEvent = EditConversationGroupUiEvent.Back) }
            }

            is EditConversationGroupUiAction.ShowDialog -> {
                _uiState.update { it.copy(uiDialog = EditConversationGroupUiDialog.TestDialog) }
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
data class EditConversationGroupUiState(
    val isLoading: Boolean = false,
    val uiEvent: EditConversationGroupUiEvent? = null,
    val uiDialog: EditConversationGroupUiDialog? = null
)

sealed interface EditConversationGroupUiEvent {
    data object Back : EditConversationGroupUiEvent
    data class Error(val errorMessage: String) : EditConversationGroupUiEvent
}

sealed interface EditConversationGroupUiDialog {
    data object TestDialog : EditConversationGroupUiDialog
}

sealed interface EditConversationGroupUiAction {
    data object BackClicked : EditConversationGroupUiAction
    data object ShowDialog : EditConversationGroupUiAction
}
