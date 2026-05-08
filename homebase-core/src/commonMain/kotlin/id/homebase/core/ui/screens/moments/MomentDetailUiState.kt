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
}

sealed interface MomentDetailUiEvent {
    data class CommentPostFailed(val message: String?) : MomentDetailUiEvent
    data class CommentEditFailed(val message: String?) : MomentDetailUiEvent
}
