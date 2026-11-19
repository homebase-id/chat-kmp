package id.homebase.core.ui.screens.feed.settings

data class FeedSettingsUiState(
    val useNativeFeed: Boolean = true,
    val iconVisible: Boolean = true,
)

sealed interface FeedSettingsUiAction {
    data object OpenFeedClicked : FeedSettingsUiAction
    data class SetIconVisible(val visible: Boolean) : FeedSettingsUiAction
    data class SetUseNativeFeed(val enabled: Boolean) : FeedSettingsUiAction
}
