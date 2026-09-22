package id.homebase.core.ui.screens.feed.following

import androidx.compose.runtime.Immutable

enum class FollowTab { Following, Followers }

// [following] and [followers] stay raw domain `String`s rather than `OdinId` so rendering a row never pays the
// OdinId SHA-256 construction cost.
@Immutable
data class FollowingUiState(
    val following: List<String> = emptyList(),
    val followers: List<String> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val selectedTab: FollowTab = FollowTab.Following,
)
