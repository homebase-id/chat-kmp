package id.homebase.core.ui.screens.filesync.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.homebase.core.filesync.FilesyncPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class FilesyncSettingsViewModel(
    private val preferences: FilesyncPreferences,
) : ViewModel() {

    private val _uiState = MutableStateFlow(FilesyncSettingsUiState(iconVisible = preferences.iconVisible.value))
    val uiState: StateFlow<FilesyncSettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            preferences.iconVisible.collect { v -> _uiState.update { it.copy(iconVisible = v) } }
        }
    }

    fun onAction(action: FilesyncSettingsUiAction) {
        when (action) {
            is FilesyncSettingsUiAction.SetIconVisible ->
                viewModelScope.launch { preferences.setIconVisible(action.visible) }
        }
    }
}
