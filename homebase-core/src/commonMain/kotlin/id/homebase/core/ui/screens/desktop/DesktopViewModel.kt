package id.homebase.core.ui.screens.desktop

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.homebase.core.settings.ThemeState
import id.homebase.core.settings.UserPreferences
import id.homebase.core.updater.UpdateAppManager
import id.homebase.core.util.PlatformInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class DesktopViewModel(
    private val userPreferences: UserPreferences,
    platformInfo: PlatformInfo,
    private val updateAppManager: UpdateAppManager,
) : ViewModel() {
    private val _uiState = MutableStateFlow(DesktopUiState(
        theme = userPreferences.theme,
        version = platformInfo.versionName,
    ))
    val uiState: StateFlow<DesktopUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            userPreferences.preferenceState.collect { state ->
                _uiState.value = _uiState.value.copy(
                    theme = state.theme
                )
            }
        }
        checkForUpdate()
    }

    fun onUiAction(action: DesktopUiAction) {
        when (action) {
            is DesktopUiAction.ToggleTheme -> {
                val newTheme = when (uiState.value.theme) {
                    ThemeState.System -> ThemeState.Light
                    ThemeState.Light -> ThemeState.Dark
                    ThemeState.Dark -> ThemeState.System
                }
                userPreferences.theme = newTheme
            }

            is DesktopUiAction.TriggerUpdate -> {
                triggerUpdate()
            }
        }
    }

    fun triggerUpdate() {
        viewModelScope.launch {
            updateAppManager.downloadUpdate()
        }
    }

    private fun checkForUpdate() {
        viewModelScope.launch {
            while (true) {
                val result = updateAppManager.checkForUpdate()
                _uiState.update { it.copy(updateAvailable = result.updateAvailable && result.canUpdate) }

                // Wait 12 hour before checking again
                delay(12 * 60 * 60 * 1000L) // 3,600,000 milliseconds = 1 hour
            }
        }
    }

}

@Immutable
data class DesktopUiState(
    val theme: ThemeState,
    val version: String,
    val updateAvailable: Boolean = false,
)

sealed interface DesktopUiAction {
    data object ToggleTheme : DesktopUiAction
    data object TriggerUpdate : DesktopUiAction
}
