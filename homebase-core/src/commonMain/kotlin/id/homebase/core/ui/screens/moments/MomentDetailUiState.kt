package id.homebase.core.ui.screens.moments

import id.homebase.api.common.OdinId
import id.homebase.chat.conversationlist.FullScreenOverlay
import id.homebase.core.moments.services.MomentCommentItem
import id.homebase.core.moments.services.MomentFeedItem
import kotlin.uuid.Uuid

data class MomentDetailUiState(
    val moment: MomentFeedItem? = null,
    val isLoading: Boolean = true,
    val fullScreenOverlay: FullScreenOverlay? = null,
    /**
     * Payload key the detail carousel should land on when first opened —
     * forwarded from the route. Null (or unmatched at render time) starts
     * the pager at page 0.
     */
    val initialPayloadKey: String? = null,

    /** Live comment list for this moment, newest-first (matches the service emit order). */
    val comments: List<MomentCommentItem> = emptyList(),
    /**
     * Self-identity used for "is this comment mine?" checks. Null while
     * credentials are still loading; UI should treat that as "not mine"
     * until it lands.
     */
    val selfOdinId: OdinId? = null,
    val commentDraft: String = "",
    val isPostingComment: Boolean = false,
    /**
     * Comment currently being edited inline. Null when no edit is active.
     * Tap an own comment's "edit" affordance to enter edit mode; cancel or
     * save to leave it.
     */
    val editingCommentId: Uuid? = null,
    val editingCommentDraft: String = "",
    val isSavingCommentEdit: Boolean = false,

    /**
     * Quick-react emoji set surfaced in the moment's reactions row and the
     * per-comment react affordance. Sourced from `UserPreferences.preferredUserReactions`
     * (same store the chat composer reads). Empty when the user hasn't
     * customised — UI falls back to a sensible built-in set.
     */
    val userDefaultReactions: List<String> = emptyList(),
)

sealed interface MomentDetailUiAction {
    /**
     * Tap on a payload in the carousel. Routes by contentType to either
     * [FullScreenOverlay.ViewMessageData] (image) or
     * [FullScreenOverlay.VideoPlayerData] (video). Other types currently
     * fall through with no action.
     */
    data class MediaClicked(val payloadKey: String) : MomentDetailUiAction

    /** Dismiss whichever full-screen viewer is showing. */
    data object CloseFullScreenOverlay : MomentDetailUiAction

    data class CommentDraftChanged(val text: String) : MomentDetailUiAction
    data object PostComment : MomentDetailUiAction

    data class StartEditComment(val commentId: Uuid) : MomentDetailUiAction
    data class EditCommentDraftChanged(val text: String) : MomentDetailUiAction
    data object SaveCommentEdit : MomentDetailUiAction
    data object CancelCommentEdit : MomentDetailUiAction

    /** Toggle a reaction on the moment itself. */
    data class ToggleReactionOnMoment(val emoji: String) : MomentDetailUiAction

    /** Toggle a reaction on one of the comments under this moment. */
    data class ToggleReactionOnComment(val commentId: Uuid, val emoji: String) : MomentDetailUiAction
}

sealed interface MomentDetailUiEvent {
    data class CommentPostFailed(val message: String?) : MomentDetailUiEvent
    data class CommentEditFailed(val message: String?) : MomentDetailUiEvent
    data class ReactionFailed(val message: String?) : MomentDetailUiEvent
}
