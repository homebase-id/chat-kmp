package id.homebase.core.ui.screens.feed.settings

import androidx.lifecycle.ViewModel
import id.homebase.core.settings.UserPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class FeedSettingsViewModel(
    private val userPreferences: UserPreferences,
) : ViewModel() {

    private val _uiState = MutableStateFlow(FeedSettingsUiState(useNativeFeed = userPreferences.useNativeFeed))
    val uiState: StateFlow<FeedSettingsUiState> = _uiState.asStateFlow()

    fun onAction(action: FeedSettingsUiAction) {
        when (action) {
            is FeedSettingsUiAction.SetUseNativeFeed -> {
                userPreferences.useNativeFeed = action.enabled
                _uiState.update { it.copy(useNativeFeed = action.enabled) }
            }
        }
    }
}
