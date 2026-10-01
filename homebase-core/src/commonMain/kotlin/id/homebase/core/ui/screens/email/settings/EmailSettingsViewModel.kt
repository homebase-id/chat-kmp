package id.homebase.core.ui.screens.email.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.homebase.core.email.EmailPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class EmailSettingsViewModel(
    private val emailPreferences: EmailPreferences,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        EmailSettingsUiState(
            iconVisible = emailPreferences.iconVisible.value,
            biometricsEnabled = emailPreferences.biometricsEnabled.value,
        )
    )
    val uiState: StateFlow<EmailSettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            emailPreferences.iconVisible.collect { v ->
                _uiState.update { it.copy(iconVisible = v) }
            }
        }
        viewModelScope.launch {
            emailPreferences.biometricsEnabled.collect { v ->
                _uiState.update { it.copy(biometricsEnabled = v) }
            }
        }
    }

    fun onAction(action: EmailSettingsUiAction) {
        when (action) {
            EmailSettingsUiAction.OpenEmailClicked -> {
                // Handled by the screen — it navigates.
            }
            is EmailSettingsUiAction.SetIconVisible -> {
                viewModelScope.launch { emailPreferences.setIconVisible(action.visible) }
            }
            is EmailSettingsUiAction.SetBiometricsEnabled -> {
                viewModelScope.launch { emailPreferences.setBiometricsEnabled(action.enabled) }
            }
        }
    }
}
