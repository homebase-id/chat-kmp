package id.homebase.core.ui.screens.feed.settings

import id.homebase.api.image.SendQuality
import id.homebase.core.settings.SendQualityApp

data class FeedSettingsUiState(
    val useNativeFeed: Boolean = true,
    val iconVisible: Boolean = true,
    val quality: SendQuality = SendQualityApp.Feed.default,
    val autoSaveIncomingMedia: Boolean = false,
)

sealed interface FeedSettingsUiAction {
    data object OpenFeedClicked : FeedSettingsUiAction
    data class SetIconVisible(val visible: Boolean) : FeedSettingsUiAction
    data class SetUseNativeFeed(val enabled: Boolean) : FeedSettingsUiAction
    data class SetQuality(val quality: SendQuality) : FeedSettingsUiAction
    data class SetAutoSaveIncomingMedia(val enabled: Boolean) : FeedSettingsUiAction
}
