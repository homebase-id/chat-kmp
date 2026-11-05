package id.homebase.core.ui.screens.calls.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.homebase.core.calling.CallsPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class CallsSettingsViewModel(
    private val preferences: CallsPreferences,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CallsSettingsUiState(enabled = preferences.enabled.value))
    val uiState: StateFlow<CallsSettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            preferences.enabled.collect { v -> _uiState.update { it.copy(enabled = v) } }
        }
    }

    fun onAction(action: CallsSettingsUiAction) {
        when (action) {
            is CallsSettingsUiAction.SetEnabled ->
                viewModelScope.launch { preferences.setEnabled(action.enabled) }
        }
    }
}
