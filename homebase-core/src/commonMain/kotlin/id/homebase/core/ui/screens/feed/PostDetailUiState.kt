package id.homebase.core.ui.screens.feed

import id.homebase.api.common.OdinId
import id.homebase.core.feed.services.CanReact
import id.homebase.core.feed.services.FeedPostItem
import id.homebase.core.feed.services.PostCommentItem
import id.homebase.core.widget.ReactionDisplayItem

// [isLoading] is intentionally NOT defaulted to true: the seed handed to stateIn(WhileSubscribed) is re-used on
// every re-subscription, so a true default flashes the spinner over already-loaded content.
data class PostDetailUiState(
    val post: FeedPostItem? = null,
    val comments: List<PostCommentItem> = emptyList(),
    val isLoading: Boolean = false,
    /** Null for a top-level comment. */
    val replyingTo: PostCommentItem? = null,
    /** Null while credentials are still loading; the UI treats that as "not mine" until it lands. */
    val selfOdinId: OdinId? = null,
    /** Null while still resolving — render nothing rather than flashing a denial that turns into an allow. */
    val canReact: CanReact? = null,
    /** Known identities only; the screen falls back to the raw domain for anyone absent. */
    val displayNames: Map<OdinId, String> = emptyMap(),
    /** Null when the sheet is closed; non-null (even empty) means it is showing. */
    val reactorsSheet: List<ReactionDisplayItem>? = null,
    val isReactorsLoading: Boolean = false,
    /** The roster only sees our own identity's rows on a post hosted elsewhere, so chips are labelled from here. */
    val reactorsCounts: Map<String, Int> = emptyMap(),
    /** True when the open sheet's roster is knowably incomplete — the post isn't ours. */
    val reactorsPartial: Boolean = false,
    val errorMessage: String? = null,
)
