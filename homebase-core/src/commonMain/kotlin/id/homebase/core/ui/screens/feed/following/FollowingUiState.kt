package id.homebase.core.ui.screens.feed.following

import androidx.compose.runtime.Immutable

/**
 * Which of the two follow lists the screen is currently showing. The screen
 * renders a [androidx.compose.material3.TabRow] with one tab per entry.
 */
enum class FollowTab { Following, Followers }

/**
 * Flat UI state for the Following/Followers screen.
 *
 * [following] and [followers] are lists of identity domain names (the raw
 * `String` the follow endpoints return — see `FollowProvider.fetchFollowing`).
 * They are kept as `String` rather than `OdinId` so the screen never pays the
 * `OdinId` SHA-256 construction cost while just rendering rows; the row's
 * avatar/initials work directly off the domain string.
 */
@Immutable
data class FollowingUiState(
    val following: List<String> = emptyList(),
    val followers: List<String> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val selectedTab: FollowTab = FollowTab.Following,
)
