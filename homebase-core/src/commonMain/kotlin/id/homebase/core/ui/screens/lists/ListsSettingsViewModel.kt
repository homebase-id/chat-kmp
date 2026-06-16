package id.homebase.core.ui.screens.lists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.homebase.core.lists.ListsPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ListsSettingsViewModel(
    private val listsPreferences: ListsPreferences,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        ListsSettingsUiState(
            iconVisible = listsPreferences.iconVisible.value,
        )
    )
    val uiState: StateFlow<ListsSettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            listsPreferences.iconVisible.collect { v ->
                _uiState.update { it.copy(iconVisible = v) }
            }
        }
    }

    fun onAction(action: ListsSettingsUiAction) {
        when (action) {
            ListsSettingsUiAction.OpenListsClicked -> {
                // Handled by the screen — it dispatches to the navigation callback.
            }
            is ListsSettingsUiAction.SetIconVisible -> {
                viewModelScope.launch { listsPreferences.setIconVisible(action.visible) }
            }
        }
    }
}
