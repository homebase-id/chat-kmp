package id.homebase.core.ui.screens.feed

import id.homebase.api.common.OdinId
import id.homebase.core.feed.services.FeedPostItem
import id.homebase.core.feed.services.PostCommentItem

/**
 * Flat state for the post-detail + comments screen. Mirrors
 * [id.homebase.core.ui.screens.moments.MomentDetailUiState] but trimmed to the feed's
 * single-post detail needs.
 *
 * [isLoading] is intentionally **not** defaulted to `true`. The seed state handed to
 * `stateIn(WhileSubscribed)` is re-used whenever the screen re-subscribes after the
 * timeout, so a `true` default flashes the spinner over already-loaded content (the
 * Moment-detail spinner bug). Instead the VM derives loading from "post not yet resolved"
 * and only reports `true` until the first timeline emission lands.
 */
data class PostDetailUiState(
    val post: FeedPostItem? = null,
    val comments: List<PostCommentItem> = emptyList(),
    val isLoading: Boolean = false,
    /** Comment the composer is replying to; null for a top-level comment. */
    val replyingTo: PostCommentItem? = null,
    /**
     * Self-identity used for "is this comment/post mine?" checks. Null while credentials
     * are still loading; the UI treats that as "not mine" until it lands.
     */
    val selfOdinId: OdinId? = null,
    val errorMessage: String? = null,
)
