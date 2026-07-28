package id.homebase.core.ui.screens.feed

import id.homebase.api.common.OdinId
import id.homebase.core.feed.services.CanReact
import id.homebase.core.feed.services.FeedPostItem
import id.homebase.core.feed.services.PostCommentItem
import id.homebase.core.widget.ReactionDisplayItem

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
    /**
     * Whether this user may react/comment on the post, per the channel's drive grants and the
     * author's own interaction setting. Null while it is still being resolved — render nothing
     * rather than flashing a denial that turns into an allow a moment later.
     */
    val canReact: CanReact? = null,
    /**
     * Reactive `odinId → resolved display name` for the post author and every comment author,
     * sourced from [id.homebase.chat.services.convo.contact.ContactService]. Known identities
     * only; the screen falls back to the raw domain for anyone absent (web `AuthorName` parity).
     */
    val displayNames: Map<OdinId, String> = emptyMap(),
    /**
     * Reactor roster for the "who reacted" sheet, or null when the sheet is closed.
     * Non-null (even empty) means the sheet is showing; [isReactorsLoading] covers the
     * in-flight fetch before the list arrives.
     */
    val reactorsSheet: List<ReactionDisplayItem>? = null,
    val isReactorsLoading: Boolean = false,
    val errorMessage: String? = null,
)
