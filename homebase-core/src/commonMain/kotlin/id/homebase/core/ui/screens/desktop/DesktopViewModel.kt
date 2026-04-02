package id.homebase.core.ui.screens.desktop

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.homebase.core.settings.ThemeState
import id.homebase.core.settings.UserPreferences
import id.homebase.core.util.PlatformInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class DesktopViewModel(
    private val userPreferences: UserPreferences,
    private val platformInfo: PlatformInfo,
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
    }

    fun onUiAction(action: DesktopUiAction) {
        when (action) {
            is DesktopUiAction.ToggleTheme -> {
                val newTheme = when (uiState.value.theme) {
                    ThemeState.System -> ThemeState.Light
                    ThemeState.Light -> ThemeState.System
                    ThemeState.Dark -> ThemeState.System
                }
                userPreferences.theme = newTheme
            }
        }
    }

}

@Immutable
data class DesktopUiState(
    val theme: ThemeState,
    val version: String,
)

sealed interface DesktopUiAction {
    data object ToggleTheme : DesktopUiAction
}
