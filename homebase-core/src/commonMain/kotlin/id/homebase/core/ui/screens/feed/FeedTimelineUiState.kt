package id.homebase.core.ui.screens.feed

import androidx.compose.runtime.Immutable
import id.homebase.core.feed.services.FeedPostItem

/**
 * Flat UI state for the native home timeline ([FeedTimelineScreen]).
 *
 * [isLoading] gates the full-screen spinner shown before the first emission of the
 * timeline; once posts (or an empty-after-load result) land, it flips false and stays
 * false. [isRefreshing] backs the pull-to-refresh indicator and is independent of the
 * cold-start spinner. [endReached] tells the infinite-scroll trigger to stop calling
 * `loadMore`.
 */
@Immutable
data class FeedTimelineUiState(
    val isLoading: Boolean = false,
    val posts: List<FeedPostItem> = emptyList(),
    val isRefreshing: Boolean = false,
    val errorMessage: String? = null,
    val endReached: Boolean = false,
)
