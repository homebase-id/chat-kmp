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

    /**
     * Whether the active user authored this moment — drives the
     * "Delete for everyone" affordance in the delete dialog. Owner-side
     * moment files have `senderOdinId == null`; received moments carry the
     * sender's OdinId, so the check is the same predicate the comment list
     * uses for "is this mine?".
     */
    val isMine: Boolean = false,

    /**
     * Confirmation dialog for moment deletion. Open from the detail screen's
     * overflow menu; the dialog itself routes to either "for me" or
     * "for everyone" depending on user choice and [isMine].
     */
    val showDeleteDialog: Boolean = false,

    /** True while the delete request is in flight. */
    val isDeleting: Boolean = false,

    /**
     * Comment ids whose delete is currently in flight. The comment row reads
     * this to disable its Edit/Delete buttons and show a small spinner while
     * the optimistic write + outbox enqueue is running. Entries are removed
     * as soon as `deleteComment` returns; the optimistic delete usually drops
     * the comment from the list at the same instant.
     */
    val deletingCommentIds: Set<Uuid> = emptySet(),

    /**
     * Comment whose delete confirmation dialog is open, or null when no
     * comment-delete dialog is showing. The dialog mirrors the moment-delete
     * dialog (for me / for everyone / cancel).
     */
    val deleteCommentDialogTarget: Uuid? = null,
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

    /** Overflow-menu "Delete" tapped — open the confirmation dialog. */
    data object RequestDeleteMoment : MomentDetailUiAction

    /** Dismiss the delete confirmation dialog without acting. */
    data object DismissDeleteDialog : MomentDetailUiAction

    /**
     * Confirm deletion of the current moment. `forEveryone = true` is only
     * surfaced by the UI when the user is the sender; the service does not
     * re-check that gate.
     */
    data class ConfirmDeleteMoment(val forEveryone: Boolean) : MomentDetailUiAction

    /** Delete affordance tapped on a comment — open the confirmation dialog. */
    data class RequestDeleteComment(val commentId: Uuid) : MomentDetailUiAction

    /** Dismiss the comment delete confirmation dialog. */
    data object DismissDeleteCommentDialog : MomentDetailUiAction

    /** Confirm deletion of a specific comment. */
    data class ConfirmDeleteComment(
        val commentId: Uuid,
        val forEveryone: Boolean,
    ) : MomentDetailUiAction
}

sealed interface MomentDetailUiEvent {
    data class CommentPostFailed(val message: String?) : MomentDetailUiEvent
    data class CommentEditFailed(val message: String?) : MomentDetailUiEvent
    data class ReactionFailed(val message: String?) : MomentDetailUiEvent

    /** Delete completed — screen should pop back to the feed. */
    data object MomentDeleted : MomentDetailUiEvent
    data class DeleteFailed(val message: String?) : MomentDetailUiEvent

    data class CommentDeleteFailed(val message: String?) : MomentDetailUiEvent
}
