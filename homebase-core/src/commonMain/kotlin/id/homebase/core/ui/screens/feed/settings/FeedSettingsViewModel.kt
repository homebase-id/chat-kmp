package id.homebase.core.ui.screens.feed.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.homebase.core.feed.FeedPreferences
import id.homebase.core.settings.UserPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class FeedSettingsViewModel(
    private val userPreferences: UserPreferences,
    private val feedPreferences: FeedPreferences,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        FeedSettingsUiState(useNativeFeed = userPreferences.useNativeFeed, iconVisible = feedPreferences.iconVisible.value),
    )
    val uiState: StateFlow<FeedSettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            feedPreferences.iconVisible.collect { v -> _uiState.update { it.copy(iconVisible = v) } }
        }
    }

    fun onAction(action: FeedSettingsUiAction) {
        when (action) {
            FeedSettingsUiAction.OpenFeedClicked -> Unit
            is FeedSettingsUiAction.SetIconVisible -> viewModelScope.launch { feedPreferences.setIconVisible(action.visible) }
            is FeedSettingsUiAction.SetUseNativeFeed -> {
                userPreferences.useNativeFeed = action.enabled
                _uiState.update { it.copy(useNativeFeed = action.enabled) }
            }
        }
    }
}
