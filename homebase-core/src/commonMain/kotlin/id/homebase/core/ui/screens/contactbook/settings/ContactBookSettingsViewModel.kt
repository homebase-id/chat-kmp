package id.homebase.core.ui.screens.contactbook.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.homebase.core.contactbook.ContactBookPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ContactBookSettingsViewModel(
    private val preferences: ContactBookPreferences,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        ContactBookSettingsUiState(
            iconVisible = preferences.iconVisible.value,
            biometricsEnabled = preferences.biometricsEnabled.value,
        )
    )
    val uiState: StateFlow<ContactBookSettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            preferences.iconVisible.collect { v -> _uiState.update { it.copy(iconVisible = v) } }
        }
        viewModelScope.launch {
            preferences.biometricsEnabled.collect { v -> _uiState.update { it.copy(biometricsEnabled = v) } }
        }
    }

    fun onAction(action: ContactBookSettingsUiAction) {
        when (action) {
            ContactBookSettingsUiAction.OpenContactsClicked -> Unit // handled by the screen
            is ContactBookSettingsUiAction.SetIconVisible ->
                viewModelScope.launch { preferences.setIconVisible(action.visible) }
            is ContactBookSettingsUiAction.SetBiometricsEnabled ->
                viewModelScope.launch { preferences.setBiometricsEnabled(action.enabled) }
        }
    }
}
