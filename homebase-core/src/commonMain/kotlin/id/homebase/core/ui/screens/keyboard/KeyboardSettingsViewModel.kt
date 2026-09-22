package id.homebase.core.ui.screens.keyboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.homebase.core.settings.UserPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class KeyboardSettingsViewModel(
    private val userPreferences: UserPreferences,
) : ViewModel() {

    private val _uiState = MutableStateFlow(KeyboardSettingsUiState())
    val uiState: StateFlow<KeyboardSettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            userPreferences.preferenceState.collect { prefs ->
                _uiState.update {
                    it.copy(
                        enterSendsMessage = prefs.enterSendsMessage,
                        arrowUpEditsLastMessage = prefs.arrowUpEditsLastMessage,
                    )
                }
            }
        }
    }

    fun onAction(action: KeyboardSettingsUiAction) {
        when (action) {
            is KeyboardSettingsUiAction.SetEnterSendsMessage -> {
                userPreferences.enterSendsMessage = action.enabled
            }

            is KeyboardSettingsUiAction.SetArrowUpEditsLastMessage -> {
                userPreferences.arrowUpEditsLastMessage = action.enabled
            }
        }
    }
}
