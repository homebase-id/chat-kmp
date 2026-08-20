package id.homebase.core.ui.screens.feed

import androidx.compose.runtime.Immutable
import id.homebase.api.common.OdinId
import id.homebase.core.feed.services.FeedPostItem
import id.homebase.core.widget.ReactionDisplayItem

// [errorMessage] separates "the read blew up" from "you follow nobody", which otherwise both render as the
// empty-feed state. Its text is diagnostic, never rendered — the error state is written from string resources.
// [reactorsSheet] null == sheet closed; non-null (even empty) == open.
@Immutable
data class FeedTimelineUiState(
    val isLoading: Boolean = false,
    val posts: List<FeedPostItem> = emptyList(),
    val isRefreshing: Boolean = false,
    val errorMessage: String? = null,
    val endReached: Boolean = false,
    val reactorsSheet: List<ReactionDisplayItem>? = null,
    val isReactorsLoading: Boolean = false,
    /** The roster only lists our own identity's rows on someone else's post, so chips are labelled from here. */
    val reactorsCounts: Map<String, Int> = emptyMap(),
    /** True when the open sheet's roster is knowably incomplete — the post isn't ours. */
    val reactorsPartial: Boolean = false,
    val selfOdinId: OdinId? = null,
)
