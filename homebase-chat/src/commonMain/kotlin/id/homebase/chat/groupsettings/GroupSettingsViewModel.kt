package id.homebase.chat.groupsettings

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.navigation.toRoute
import id.homebase.core.ui.navigation.Route
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class GroupSettingsViewModel(
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    val route = savedStateHandle.toRoute<Route.GroupSettings>()
    private val _uiState = MutableStateFlow(GroupSettingsUiState(text = route.conversationId))
    val uiState: StateFlow<GroupSettingsUiState> = _uiState.asStateFlow()

    fun onUiAction(action: GroupSettingsUiAction) {
        when (action) {
            is GroupSettingsUiAction.BackClicked -> _uiState.update { it.copy(uiEvent = GroupSettingsUiEvent.Back)}
        }
    }

    fun eventConsumed() {
        _uiState.update { it.copy(uiEvent = null) }
    }
}