package id.homebase.core.ui.screens.moments

import id.homebase.api.common.OdinId
import id.homebase.chat.conversationlist.FullScreenOverlay
import id.homebase.chat.services.ChatDeliveryStatus
import id.homebase.core.moments.services.MomentCommentItem
import id.homebase.core.moments.services.MomentFeedItem
import id.homebase.core.moments.services.MomentsRecipientId
import id.homebase.core.moments.services.MomentsRecipientsSnapshot
import kotlin.uuid.Uuid
import org.jetbrains.compose.resources.StringResource

data class MomentDetailUiState(
    val moment: MomentFeedItem? = null,
    val isLoading: Boolean = true,
    /**
     * Coil model for a just-posted moment whose payloads haven't landed yet
     * (photo file path, or a video's poster-frame JPEG bytes). Lets the
     * reels/detail media area show the picked media + a spinner during the
     * "Preparing…" window instead of a black backdrop — mirrors the timeline
     * card's placeholder behaviour. Null once real payloads arrive.
     */
    val pendingLocalPreviewModel: Any? = null,
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
     * True while the moment's description is being edited inline in the detail
     * panel. Only the author (see [isMine]) can enter this state. [descriptionDraft]
     * holds the in-progress text; [isSavingDescription] is set while the
     * `updateMoment` write is in flight.
     */
    val isEditingDescription: Boolean = false,
    val descriptionDraft: String = "",
    val isSavingDescription: Boolean = false,

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
     * True while the "Save current" flow is decrypting (and, for HLS video,
     * remuxing) the visible carousel payload to a cache file. Drives the
     * overflow-menu spinner so the user knows the save is in progress — the
     * decrypt/remux is the slow part; the device-gallery write that follows is
     * near-instant.
     */
    val isSavingMedia: Boolean = false,

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

    /**
     * Audience the moment was shared with — group titles and individual
     * domains, resolved by the VM from the moment's `MomentSource`. Null when
     * the moment has no recorded source (legacy posts, local-only) or when
     * the lookup tables haven't loaded yet. Empty list inside the wrapper is
     * not a valid state — the VM emits null in that case.
     */
    val sharedWith: SharedWithDisplay? = null,

    /**
     * Whether the "Shared with" row is expanded. Hoisted into the VM (vs. a
     * local rememberSaveable) because the first expansion triggers an
     * on-demand `getTransferHistory` fetch — keeping the toggle next to the
     * loader lets the VM scope the network call to the open state and avoid
     * doing it on cold load.
     */
    val sharedWithExpanded: Boolean = false,

    /** True while a transfer-history fetch is in flight. */
    val isTransferHistoryLoading: Boolean = false,

    /**
     * Per-recipient delivery state for the author's own moment. Empty for
     * received moments (the server only returns history to the sender) and
     * before the first expansion. Reuses chat's status/error-text mappings
     * so the UI presentation matches the chat MessageInfo screen.
     */
    val recipientDeliveries: List<RecipientDeliveryUiModel> = emptyList(),

    /**
     * Flat list of individual recipients (OdinId + resolved display name)
     * derived from the moment's recipients list, minus the active user.
     * Drives the collapsed avatar stack and the expanded recipient list for
     * received (non-authored) moments. For [SharedWithDisplay.Private] this
     * is empty.
     */
    val recipientAvatars: List<RecipientBaseUiModel> = emptyList(),

    /**
     * Whether the "who reacted" bottom sheet is open. Opening it triggers
     * an on-demand fetch via `MomentActionService.getReactionsForMoment` so
     * the sheet only pays the round-trip when the user actually asks. The
     * chips above the sheet keep using `moment.reactionPreview`, which is
     * already live-updated by the feed.
     */
    val showReactionsSheet: Boolean = false,

    /** True while a reaction-list fetch is in flight. */
    val isReactionsLoading: Boolean = false,

    /**
     * Per-reactor rows for the bottom sheet — one entry per (odinId, emoji)
     * pair returned by the server. Resolved against the user's contact list
     * for display names so the sheet matches chat's MessageInfo "Reactions"
     * section visually and semantically.
     */
    val reactions: List<MomentReactionUiModel> = emptyList(),

    /**
     * Whether the "Add people" recipient picker sheet is open. Author-only
     * (gated on [isMine]); lets the author widen an already-posted moment's
     * audience without re-composing.
     */
    val showAddRecipientsSheet: Boolean = false,

    /**
     * Recipient candidates for the "Add people" sheet — the same MRU /
     * groups / contacts snapshot the compose-time audience picker uses,
     * mirrored from `MomentsRecipientLookupService`.
     */
    val addRecipientsSnapshot: MomentsRecipientsSnapshot = MomentsRecipientsSnapshot.empty(),

    /** Search query inside the "Add people" sheet. */
    val addRecipientsQuery: String = "",

    /**
     * Newly-picked recipients in the "Add people" sheet (the additions only —
     * recipients already on the moment render as locked and aren't selectable).
     */
    val addRecipientsSelected: Set<MomentsRecipientId> = emptySet(),

    /** True while the add-recipients update is being enqueued. */
    val isAddingRecipients: Boolean = false,
)

/**
 * One row in the "who reacted" bottom sheet. Shape mirrors chat's
 * `ReactionUiModel` in MessageInfoUiState — same fields, same render — but
 * lives in the moments namespace so the surfaces can evolve independently.
 */
data class MomentReactionUiModel(
    val odinId: OdinId,
    val displayName: String,
    val emoji: String,
)

/**
 * Lightweight recipient row data — OdinId for the avatar lookup, displayName
 * for the row label. Distinct from [RecipientDeliveryUiModel] (which also
 * carries delivery state) so the collapsed avatar stack and the !isMine
 * expanded list don't drag along loading/status fields they don't use.
 */
data class RecipientBaseUiModel(
    val odinId: OdinId,
    val displayName: String,
)

/**
 * Per-recipient delivery row for the moment's expanded "Shared with" surface.
 * Shape mirrors `RecipientStatusUiModel` from MessageInfoUiState — same fields,
 * same chat mappings — but lives in the moments namespace so the two screens
 * can evolve independently.
 */
data class RecipientDeliveryUiModel(
    val odinId: String,
    val displayName: String,
    val deliveryStatus: ChatDeliveryStatus,
    val errorDetailRes: StringResource? = null,
)

/**
 * Already-resolved audience presentation for the detail screen. Group titles
 * and individual domains are pre-resolved by the VM so the composable can
 * render synchronously — the only thing left to the screen is layout +
 * localisation of the row label and any +N suffix.
 *
 * [Private] is author-side: the user kept this moment with no recipients.
 *   Covers two indistinguishable cases — no recorded source (legacy posts),
 *   or an explicitly empty audience (saved-just-for-me post).
 * [JustYou] is receiver-side: an inbound 1:1 share where the active user is
 *   the only recipient. Distinct from Private so the receiver isn't told
 *   their own inbound post is "private" (it isn't — it was shared with them).
 * [Recipients.entries] is non-empty by construction — the VM emits null
 *   instead of an empty Recipients while a lookup is still loading.
 */
sealed interface SharedWithDisplay {
    data object Private : SharedWithDisplay
    data object JustYou : SharedWithDisplay
    data class Recipients(val entries: List<SharedWithEntry>) : SharedWithDisplay
}

sealed interface SharedWithEntry {
    data class Group(val name: String, val memberCount: Int) : SharedWithEntry
    data class Individual(val name: String) : SharedWithEntry
    /**
     * Conversation-sourced moment. `name` is null when the conversation list
     * hasn't loaded yet (cold start) so the composable can fall back to a
     * generic localised label rather than showing a blank row.
     */
    data class Conversation(val name: String?) : SharedWithEntry
}

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

    /**
     * Edit the moment's own description (owner-only). Start seeds the draft
     * with the current description; Save commits via `updateMoment`; Cancel
     * discards.
     */
    data object StartEditDescription : MomentDetailUiAction
    data class DescriptionDraftChanged(val text: String) : MomentDetailUiAction
    data object SaveDescriptionEdit : MomentDetailUiAction
    data object CancelDescriptionEdit : MomentDetailUiAction

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

    /**
     * Open / close the recipient list under the "Shared with" row. The first
     * expand on an authored moment triggers a transfer-history fetch so the
     * delivery rows can populate; subsequent toggles just flip visibility.
     */
    data class ToggleSharedWithExpansion(val expanded: Boolean) : MomentDetailUiAction

    /**
     * Share-button tap in the full-screen image viewer. Decrypts the payload,
     * writes a cleartext copy into the share_outbound sweep dir, and emits
     * [MomentDetailUiEvent.ShareFileReady] for the screen to hand to the
     * platform share sheet.
     */
    data class ShareMedia(val payloadKey: String) : MomentDetailUiAction

    /**
     * Overflow-menu "Save current" tapped. Decrypts the given carousel payload
     * (the one currently on screen) and emits [MomentDetailUiEvent.MediaSaveReady]
     * so the screen can hand it to the platform save-to-device flow. HLS video
     * is remuxed to a playable MP4 first.
     */
    data class SaveMedia(val payloadKey: String) : MomentDetailUiAction

    /** Open the "who reacted" bottom sheet and fetch the reactor list. */
    data object OpenReactionsSheet : MomentDetailUiAction

    /** Dismiss the "who reacted" bottom sheet. */
    data object DismissReactionsSheet : MomentDetailUiAction

    /**
     * Overflow-menu "Add people" tapped — open the recipient picker sheet to
     * widen the moment's audience. Author-only; the VM re-checks [isMine].
     */
    data object RequestAddRecipients : MomentDetailUiAction

    /** Dismiss the "Add people" sheet without sending. */
    data object DismissAddRecipientsSheet : MomentDetailUiAction

    /** Search query changed inside the "Add people" sheet. */
    data class AddRecipientsQueryChanged(val text: String) : MomentDetailUiAction

    /** Toggle a candidate recipient's selection in the "Add people" sheet. */
    data class ToggleAddRecipient(val id: MomentsRecipientId) : MomentDetailUiAction

    /**
     * Confirm the additions — re-distributes the existing media to the newly
     * picked recipients via `MomentsPostSenderService.addRecipientsToMoment`.
     */
    data object ConfirmAddRecipients : MomentDetailUiAction
}

sealed interface MomentDetailUiEvent {
    data class CommentPostFailed(val message: String?) : MomentDetailUiEvent
    data class CommentEditFailed(val message: String?) : MomentDetailUiEvent
    data class DescriptionEditFailed(val message: String?) : MomentDetailUiEvent
    data class ReactionFailed(val message: String?) : MomentDetailUiEvent

    /** Delete completed — screen should pop back to the feed. */
    data object MomentDeleted : MomentDetailUiEvent
    data class DeleteFailed(val message: String?) : MomentDetailUiEvent

    data class CommentDeleteFailed(val message: String?) : MomentDetailUiEvent

    /**
     * Decrypted payload is written to [filePath] under the share_outbound sweep
     * dir; the screen should hand the path to the platform share sheet.
     */
    data class ShareFileReady(val filePath: String) : MomentDetailUiEvent
    data class ShareFailed(val message: String?) : MomentDetailUiEvent

    /**
     * Decrypted (and, for HLS, remuxed) payload is written to [filePath]; the
     * screen should hand it + [suggestedName] to `FileSystemHandler.saveFile`
     * to write it into the device gallery / Downloads.
     */
    data class MediaSaveReady(
        val filePath: String,
        val suggestedName: String,
    ) : MomentDetailUiEvent
    data class MediaSaveFailed(val message: String?) : MomentDetailUiEvent

    /** Audience successfully widened by [count] new recipients. */
    data class RecipientsAdded(val count: Int) : MomentDetailUiEvent
    data class AddRecipientsFailed(val message: String?) : MomentDetailUiEvent
}
