package id.homebase.core.ui.screens.feed.following

import androidx.compose.runtime.Immutable

// Suggested: connections you don't follow yet, the easiest people to start a feed with.
enum class FollowTab { Following, Followers, Suggested }

// [following] and [followers] stay raw domain `String`s rather than `OdinId` so rendering a row never pays the
// OdinId SHA-256 construction cost.
@Immutable
data class FollowingUiState(
    val following: List<String> = emptyList(),
    val followers: List<String> = emptyList(),
    val connections: List<String> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val selectedTab: FollowTab = FollowTab.Following,
    val isFollowByAddressOpen: Boolean = false,
) {
    /** Connections not followed yet, in connection order. */
    val suggested: List<String>
        get() {
            val followed = following.mapTo(HashSet()) { it.lowercase() }
            return connections.filter { it.lowercase() !in followed }
        }
}
