package id.homebase.core.ui.screens.feed.following

import androidx.compose.runtime.Immutable
import id.homebase.api.client.follow.FollowerSuggestion

// Suggested: connections you don't follow yet, the easiest people to start a feed with.
enum class FollowTab { Following, Followers, Suggested }

// The lists stay raw domain `String`s rather than `OdinId` so rendering a row never pays the OdinId SHA-256
// construction cost.
@Immutable
data class FollowingUiState(
    val following: List<String> = emptyList(),
    val followers: List<String> = emptyList(),
    val connections: List<String> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val selectedTab: FollowTab = FollowTab.Following,
    val isFollowByAddressOpen: Boolean = false,
    /** Developer flag: People you may know, on the Suggested tab. */
    val advancedFeed: Boolean = false,
    val peopleYouMayKnow: List<FollowerSuggestion> = emptyList(),
    val peopleYouMayKnowLoading: Boolean = false,
    /** "Based on N of your M connections"; null until loaded. */
    val peopleYouMayKnowBasis: Pair<Int, Int>? = null,
) {
    /** Connections not followed yet, in connection order. */
    val suggested: List<String>
        get() {
            val followed = following.mapTo(HashSet()) { it.lowercase() }
            return connections.filter { it.lowercase() !in followed }
        }

    /** People you may know, less anyone followed since they were computed. */
    val peopleYouMayKnowNotFollowed: List<FollowerSuggestion>
        get() {
            val followed = following.mapTo(HashSet()) { it.lowercase() }
            return peopleYouMayKnow.filter { it.odinId.lowercase() !in followed }
        }
}
