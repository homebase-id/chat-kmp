package id.homebase.core.ui.screens.feed

data class FeedUiState(
    val isLoading: Boolean = true,
    val feedUrl: String = "",
    val error: String? = null,
    val isDarkMode: Boolean = false,
    val credentialsReady: Boolean = false,
    val injectionScript: String? = null,
)

sealed interface FeedUiAction {
    data object RetryClicked : FeedUiAction
    data object PageStarted : FeedUiAction
    data object PageFinished : FeedUiAction
    data class PageError(val message: String) : FeedUiAction
}
