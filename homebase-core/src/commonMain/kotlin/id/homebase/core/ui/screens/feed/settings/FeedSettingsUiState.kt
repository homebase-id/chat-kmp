package id.homebase.core.ui.screens.feed.settings

data class FeedSettingsUiState(
    val useNativeFeed: Boolean = true,
)

sealed interface FeedSettingsUiAction {
    data class SetUseNativeFeed(val enabled: Boolean) : FeedSettingsUiAction
}
