package id.homebase.core.ui.screens.feed.settings

import id.homebase.core.util.AlbumSaver
import id.homebase.core.settings.SendQualityApp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.homebase.core.feed.FeedPreferences
import id.homebase.core.settings.UserPreferences
import id.homebase.core.settings.sendQuality
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class FeedSettingsViewModel(
    private val userPreferences: UserPreferences,
    private val feedPreferences: FeedPreferences,
    albumSaver: AlbumSaver,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        FeedSettingsUiState(
            useNativeFeed = userPreferences.useNativeFeed,
            iconVisible = feedPreferences.iconVisible.value,
            quality = userPreferences.sendQuality(SendQualityApp.Feed),
            autoSaveIncomingMedia = userPreferences.feedAutoSaveIncomingMedia,
            savesInBackground = albumSaver.savesUnasked,
        ),
    )
    val uiState: StateFlow<FeedSettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            feedPreferences.iconVisible.collect { v -> _uiState.update { it.copy(iconVisible = v) } }
        }
        // The composer's quality chip writes the same preference.
        viewModelScope.launch {
            userPreferences.preferenceState.collect { prefs -> _uiState.update { it.copy(quality = prefs.sendQuality(SendQualityApp.Feed)) } }
        }
    }

    fun onAction(action: FeedSettingsUiAction) {
        when (action) {
            is FeedSettingsUiAction.SetIconVisible -> viewModelScope.launch { feedPreferences.setIconVisible(action.visible) }
            is FeedSettingsUiAction.SetQuality -> userPreferences.setSendQuality(SendQualityApp.Feed, action.quality)
            is FeedSettingsUiAction.SetAutoSaveIncomingMedia -> {
                userPreferences.feedAutoSaveIncomingMedia = action.enabled
                _uiState.update { it.copy(autoSaveIncomingMedia = action.enabled) }
            }
            is FeedSettingsUiAction.SetUseNativeFeed -> {
                userPreferences.useNativeFeed = action.enabled
                _uiState.update { it.copy(useNativeFeed = action.enabled) }
            }
        }
    }
}
