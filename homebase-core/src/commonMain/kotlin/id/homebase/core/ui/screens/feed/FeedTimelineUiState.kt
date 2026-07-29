package id.homebase.core.ui.screens.feed

import androidx.compose.runtime.Immutable
import id.homebase.api.common.OdinId
import id.homebase.core.feed.services.FeedPostItem
import id.homebase.core.widget.ReactionDisplayItem

/**
 * Flat UI state for the native home timeline ([FeedTimelineScreen]).
 *
 * [isLoading] gates the full-screen spinner shown before the first emission of the
 * timeline; once posts (or an empty-after-load result) land, it flips false and stays
 * false. [isRefreshing] backs the pull-to-refresh indicator and is independent of the
 * cold-start spinner. [endReached] tells the infinite-scroll trigger to stop calling
 * `loadMore`.
 *
 * [reactorsSheet] / [isReactorsLoading] / [selfOdinId] back the inline "who reacted" sheet
 * opened from a tweet/media post's reaction facepile (articles route to the detail screen
 * instead). [reactorsSheet] null == sheet closed; non-null (even empty) == open.
 */
@Immutable
data class FeedTimelineUiState(
    val isLoading: Boolean = false,
    val posts: List<FeedPostItem> = emptyList(),
    val isRefreshing: Boolean = false,
    val errorMessage: String? = null,
    val endReached: Boolean = false,
    val reactorsSheet: List<ReactionDisplayItem>? = null,
    val isReactorsLoading: Boolean = false,
    /**
     * Authoritative `emoji → count` for the open sheet, taken from the post header. The roster
     * itself can only ever list our own identity's rows on someone else's post, so the chips are
     * labelled from here rather than from [reactorsSheet].
     */
    val reactorsCounts: Map<String, Int> = emptyMap(),
    /** True when the open sheet's roster is knowably incomplete — i.e. the post isn't ours. */
    val reactorsPartial: Boolean = false,
    val selfOdinId: OdinId? = null,
)
