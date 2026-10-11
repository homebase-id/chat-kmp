package id.homebase.core.ui.screens.desktop

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.homebase.core.settings.ThemeState
import id.homebase.core.settings.UserPreferences
import id.homebase.core.updater.AppUpdates
import id.homebase.core.updater.canOfferUpdate
import id.homebase.core.util.PlatformInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.hours

class DesktopViewModel(
    private val userPreferences: UserPreferences,
    platformInfo: PlatformInfo,
    private val appUpdates: AppUpdates,
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
        viewModelScope.launch {
            appUpdates.latest.collect { latest ->
                _uiState.update { it.copy(updateAvailable = latest.canOfferUpdate) }
            }
        }
        pollForUpdates()
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

            is DesktopUiAction.SetTheme -> userPreferences.theme = action.theme

            is DesktopUiAction.TriggerUpdate -> {
                triggerUpdate()
            }
        }
    }

    fun triggerUpdate() {
        viewModelScope.launch {
            appUpdates.download()
        }
    }

    private fun pollForUpdates() {
        viewModelScope.launch {
            while (true) {
                appUpdates.check()
                delay(12.hours)
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
    data class SetTheme(val theme: ThemeState) : DesktopUiAction
    data object TriggerUpdate : DesktopUiAction
}
