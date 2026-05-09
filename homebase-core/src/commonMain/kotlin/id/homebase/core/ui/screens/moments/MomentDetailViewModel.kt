package id.homebase.core.ui.screens.moments

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import co.touchlab.kermit.Logger
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.common.OdinId
import id.homebase.chat.conversationlist.FullScreenOverlay
import id.homebase.core.moments.services.MomentActionService
import id.homebase.core.moments.services.MomentCommentsService
import id.homebase.core.moments.services.MomentsFeedService
import id.homebase.core.moments.services.MomentsPostSenderService
import id.homebase.core.settings.UserPreferences
import id.homebase.core.ui.navigation.Route
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.Instant
import kotlin.uuid.Uuid

private const val TAG = "MomentDetailViewModel"

/**
 * Resolves a single moment by id from the live feed flow. We deliberately
 * source from [MomentsFeedService] rather than re-querying the DB:
 *  - The user lands here from the feed, where the moment is already in
 *    memory — no extra read needed for the happy path.
 *  - When new sync batches arrive (e.g. a description edit replays from
 *    another device), [MomentsFeedService] re-emits and the detail screen
 *    re-renders automatically.
 *  - On cold start before the feed has loaded, `moment` stays null and the
 *    screen shows a loading state until the feed populates.
 *
 * Comments stream from [MomentCommentsService.commentsFor] for the same
 * `momentId` — the service handles cold-load + live event-bus updates so the
 * VM just merges the snapshot into uiState.
 */
class MomentDetailViewModel(
    savedStateHandle: SavedStateHandle,
    feedService: MomentsFeedService,
    private val commentsService: MomentCommentsService,
    private val postSender: MomentsPostSenderService,
    private val actionService: MomentActionService,
    private val credentialsManager: CredentialsManager,
    private val userPreferences: UserPreferences,
) : ViewModel() {

    private val route = savedStateHandle.toRoute<Route.MomentDetail>()
    private val momentId: Uuid = Uuid.parse(route.momentId)
    private val initialPayloadKey: String? = route.initialPayloadKey

    private val _overlay = MutableStateFlow<FullScreenOverlay?>(null)
    private val _selfOdinId = MutableStateFlow<OdinId?>(null)

    /** Compose-screen-local state for the comments section. */
    private data class CommentLocalState(
        val draft: String = "",
        val isPosting: Boolean = false,
        val editingId: Uuid? = null,
        val editingDraft: String = "",
        val isSavingEdit: Boolean = false,
    )

    private val _commentLocal = MutableStateFlow(CommentLocalState())

    private val _events = MutableSharedFlow<MomentDetailUiEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<MomentDetailUiEvent> = _events.asSharedFlow()

    /**
     * Snapshot at construction time — `UserPreferences.preferredUserReactions`
     * is a plain `var`, not a flow. Chat reads it the same way. If/when the
     * preference becomes reactive we can promote this into the combine.
     */
    private val userDefaultReactions: List<String> =
        userPreferences.preferredUserReactions.takeIf { it.isNotEmpty() }
            ?: listOf("❤️", "😂", "😮", "😢", "🔥", "👏")

    init {
        // Self-identity for "is this comment mine" checks. Owner-side files
        // also tend to have a null senderOdinId, so the UI's "mine" predicate
        // accepts either null or a match on this id.
        viewModelScope.launch {
            _selfOdinId.value = credentialsManager.getActiveCredentials()?.domain
        }
    }

    val uiState: StateFlow<MomentDetailUiState> = combine(
        feedService.feed,
        _overlay,
        commentsService.commentsFor(momentId),
        _commentLocal,
        _selfOdinId,
    ) { feed, overlay, comments, local, self ->
        val match = feed.firstOrNull { it.id == momentId }
        MomentDetailUiState(
            moment = match,
            isLoading = match == null,
            fullScreenOverlay = overlay,
            initialPayloadKey = initialPayloadKey,
            comments = comments,
            selfOdinId = self,
            commentDraft = local.draft,
            isPostingComment = local.isPosting,
            editingCommentId = local.editingId,
            editingCommentDraft = local.editingDraft,
            isSavingCommentEdit = local.isSavingEdit,
            userDefaultReactions = userDefaultReactions,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        MomentDetailUiState(initialPayloadKey = initialPayloadKey),
    )

    @OptIn(ExperimentalEncodingApi::class)
    fun onAction(action: MomentDetailUiAction) {
        when (action) {
            is MomentDetailUiAction.MediaClicked -> {
                val moment = uiState.value.moment ?: return
                val payload = moment.payloads.firstOrNull { it.key == action.payloadKey } ?: return
                val contentType = payload.contentType ?: ""

                when {
                    contentType.startsWith("image/") -> {
                        _overlay.value = FullScreenOverlay.ViewMessageData(
                            messageId = moment.id,
                            // Empty title for moments — the chat viewer renders this
                            // in its top bar; we don't have an author display name to
                            // surface here.
                            title = "",
                            userDate = Instant.fromEpochMilliseconds(moment.userDateMs),
                            // The chat viewer treats `content` as markdown for the
                            // caption — moment description is plain text but markdown
                            // tolerates it.
                            content = moment.description,
                            fileId = moment.fileId,
                            driveId = moment.driveId,
                            payloads = moment.payloads,
                            keyHeader = moment.keyHeader,
                            selectedPayloadKey = action.payloadKey,
                        )
                    }

                    contentType.startsWith("video/") ||
                            contentType == "application/vnd.apple.mpegurl" -> {
                        val ivBytes = payload.iv?.let { Base64.decode(it) }
                        // The video player needs a per-payload KeyHeader (the
                        // payload's IV + the moment's master AES key). If the IV
                        // is somehow missing, fall back to a 16-byte zero IV so
                        // the surface still renders the thumbnail; playback will
                        // fail loudly which is better than a silent no-op.
                        _overlay.value = FullScreenOverlay.VideoPlayerData(
                            fileId = moment.fileId,
                            driveId = moment.driveId,
                            payloadKey = action.payloadKey,
                            keyHeader = KeyHeader(
                                iv = ivBytes ?: ByteArray(16),
                                aesKey = moment.keyHeader.aesKey,
                            ),
                            payload = payload,
                            localFilePath = null,
                            uploadMessageId = null,
                        )
                    }

                    // Audio / document / other content types currently no-op.
                    // Add branches here if/when moments grow to support them.
                    else -> Unit
                }
            }

            MomentDetailUiAction.CloseFullScreenOverlay -> {
                _overlay.value = null
            }

            is MomentDetailUiAction.CommentDraftChanged ->
                _commentLocal.update { it.copy(draft = action.text) }

            MomentDetailUiAction.PostComment -> postComment()

            is MomentDetailUiAction.StartEditComment -> {
                val target = uiState.value.comments.firstOrNull { it.id == action.commentId } ?: return
                _commentLocal.update {
                    it.copy(editingId = action.commentId, editingDraft = target.body)
                }
            }

            is MomentDetailUiAction.EditCommentDraftChanged ->
                _commentLocal.update { it.copy(editingDraft = action.text) }

            MomentDetailUiAction.SaveCommentEdit -> saveCommentEdit()

            MomentDetailUiAction.CancelCommentEdit ->
                _commentLocal.update { it.copy(editingId = null, editingDraft = "") }

            is MomentDetailUiAction.ToggleReactionOnMoment -> toggleMomentReaction(action.emoji)

            is MomentDetailUiAction.ToggleReactionOnComment ->
                toggleCommentReaction(action.commentId, action.emoji)
        }
    }

    private fun toggleMomentReaction(emoji: String) {
        viewModelScope.launch {
            try {
                actionService.toggleReactionOnMoment(momentId, emoji)
            } catch (t: Throwable) {
                Logger.e(throwable = t, tag = TAG) {
                    "toggleReactionOnMoment failed: ${t.message}"
                }
                _events.tryEmit(MomentDetailUiEvent.ReactionFailed(t.message))
            }
        }
    }

    private fun toggleCommentReaction(commentId: Uuid, emoji: String) {
        viewModelScope.launch {
            try {
                actionService.toggleReactionOnComment(commentId, emoji)
            } catch (t: Throwable) {
                Logger.e(throwable = t, tag = TAG) {
                    "toggleReactionOnComment failed: ${t.message}"
                }
                _events.tryEmit(MomentDetailUiEvent.ReactionFailed(t.message))
            }
        }
    }

    private fun postComment() {
        val local = _commentLocal.value
        val body = local.draft.trim()
        if (body.isEmpty() || local.isPosting) return

        _commentLocal.update { it.copy(isPosting = true) }
        viewModelScope.launch {
            try {
                postSender.postComment(
                    momentId = momentId,
                    attachments = emptyList(),
                    body = body,
                )
                _commentLocal.update { it.copy(draft = "", isPosting = false) }
            } catch (t: Throwable) {
                Logger.e(throwable = t, tag = TAG) { "postComment failed: ${t.message}" }
                _commentLocal.update { it.copy(isPosting = false) }
                _events.tryEmit(MomentDetailUiEvent.CommentPostFailed(t.message))
            }
        }
    }

    private fun saveCommentEdit() {
        val local = _commentLocal.value
        val commentId = local.editingId ?: return
        val body = local.editingDraft.trim()
        if (body.isEmpty() || local.isSavingEdit) return

        // The version tag is needed to submit; if we don't have one yet (still
        // optimistic), bail rather than racing with the in-flight initial post.
        val current = uiState.value.comments.firstOrNull { it.id == commentId }
        val versionTag = current?.versionTag
        if (versionTag == null) {
            _events.tryEmit(
                MomentDetailUiEvent.CommentEditFailed(
                    "Comment is still being posted — try again in a moment."
                )
            )
            return
        }

        _commentLocal.update { it.copy(isSavingEdit = true) }
        viewModelScope.launch {
            try {
                postSender.updateComment(
                    commentUniqueId = commentId,
                    versionTag = versionTag,
                    body = body,
                )
                _commentLocal.update {
                    it.copy(editingId = null, editingDraft = "", isSavingEdit = false)
                }
            } catch (t: Throwable) {
                Logger.e(throwable = t, tag = TAG) { "updateComment failed: ${t.message}" }
                _commentLocal.update { it.copy(isSavingEdit = false) }
                _events.tryEmit(MomentDetailUiEvent.CommentEditFailed(t.message))
            }
        }
    }
}
