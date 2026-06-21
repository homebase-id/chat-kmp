package id.homebase.core.ui.screens.feed

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.common.OdinId
import id.homebase.chat.services.builder.AttachmentInput
import id.homebase.core.feed.services.FeedPostItem
import id.homebase.core.feed.services.FeedPostSenderService
import id.homebase.core.feed.services.FeedTimelineService
import id.homebase.core.feed.services.PostCommentItem
import id.homebase.core.feed.services.PostCommentsService
import id.homebase.core.feed.services.PostReactionService
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

private const val TAG = "PostDetailViewModel"

/**
 * Resolves a single feed post by id from [FeedTimelineService.timeline] and streams its
 * comments from [PostCommentsService.commentsFor]. We deliberately source the post from the
 * live timeline rather than re-querying the DB:
 *  - The user lands here from the feed, where the post is already in memory.
 *  - When new sync batches arrive (an edit replays from another device), the timeline
 *    re-emits and the detail re-renders automatically.
 *  - On cold start before the timeline has loaded, [PostDetailUiState.post] stays null and
 *    the screen shows a spinner until the first emission lands.
 *
 * Mirrors [id.homebase.core.ui.screens.moments.MomentDetailViewModel] but trimmed: the feed
 * detail has no audience/recipient surfaces, and loading is derived from "first timeline
 * emission seen" so the seed state can keep `isLoading = false` and avoid the Moment-detail
 * spinner flash on WhileSubscribed re-subscription.
 */
class PostDetailViewModel(
    private val postId: Uuid,
    private val timelineService: FeedTimelineService,
    private val commentsService: PostCommentsService,
    private val reactionService: PostReactionService,
    private val senderService: FeedPostSenderService,
    private val credentialsManager: CredentialsManager,
) : ViewModel() {

    private val _selfOdinId = MutableStateFlow<OdinId?>(null)
    private val _replyingTo = MutableStateFlow<PostCommentItem?>(null)

    private val _events = MutableSharedFlow<PostDetailEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<PostDetailEvent> = _events.asSharedFlow()

    /**
     * Flips to `true` the first time the timeline emits, so a post that resolves to null
     * after that is treated as "not found" rather than "still loading". Until then the
     * screen shows the spinner.
     */
    private val _timelineEmitted = MutableStateFlow(false)

    init {
        timelineService.start()
        viewModelScope.launch {
            _selfOdinId.value = credentialsManager.getActiveCredentials()?.domain
        }
    }

    val uiState: StateFlow<PostDetailUiState> = combine(
        timelineService.timeline
            .onEach { _timelineEmitted.value = true }
            .map { feed -> feed.firstOrNull { it.id == postId } },
        commentsService.commentsFor(postId),
        _replyingTo,
        _selfOdinId,
        _timelineEmitted,
    ) { post, comments, replyingTo, self, timelineEmitted ->
        PostDetailUiState(
            post = post,
            comments = comments,
            // Loading only until the first timeline emission lands. A null post AFTER
            // that means the post isn't on either source drive — not "still loading" —
            // so the spinner doesn't spin forever. Seeded false so a WhileSubscribed
            // re-subscription never flashes the spinner over loaded content.
            isLoading = !timelineEmitted && post == null,
            replyingTo = replyingTo,
            selfOdinId = self,
            errorMessage = null,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        PostDetailUiState(),
    )

    // -------------------- COMMENTS --------------------

    /**
     * Post a top-level comment, or a reply when [PostDetailUiState.replyingTo] is set. The
     * send is fire-and-forget through the outbox; the optimistic write surfaces the comment
     * in the list immediately. Clears the reply target on success.
     */
    fun postComment(text: String, attachment: AttachmentInput?) {
        val body = text.trim()
        if (body.isEmpty() && attachment == null) return
        val replyTo = _replyingTo.value?.id
        viewModelScope.launch {
            try {
                commentsService.postComment(
                    postId = postId,
                    body = body,
                    attachment = attachment,
                    replyToCommentId = replyTo,
                )
                _replyingTo.value = null
            } catch (e: Exception) {
                Logger.e(throwable = e, tag = TAG) { "postComment failed: ${e.message}" }
                _events.tryEmit(PostDetailEvent.ShowSnackbar(e.message))
            }
        }
    }

    fun startReply(comment: PostCommentItem) {
        _replyingTo.value = comment
    }

    fun cancelReply() {
        _replyingTo.value = null
    }

    fun editComment(comment: PostCommentItem, newBody: String) {
        val versionTag = comment.versionTag ?: run {
            Logger.w(tag = TAG) { "editComment: comment ${comment.id} has no versionTag" }
            _events.tryEmit(PostDetailEvent.ShowSnackbar(null))
            return
        }
        val body = newBody.trim()
        if (body.isEmpty()) return
        viewModelScope.launch {
            try {
                commentsService.updateComment(
                    commentUniqueId = comment.id,
                    versionTag = versionTag,
                    body = body,
                )
            } catch (e: Exception) {
                Logger.e(throwable = e, tag = TAG) { "editComment failed: ${e.message}" }
                _events.tryEmit(PostDetailEvent.ShowSnackbar(e.message))
            }
        }
    }

    fun deleteComment(comment: PostCommentItem) {
        viewModelScope.launch {
            try {
                commentsService.removeComment(comment.id)
                if (_replyingTo.value?.id == comment.id) _replyingTo.value = null
            } catch (e: Exception) {
                Logger.e(throwable = e, tag = TAG) { "deleteComment failed: ${e.message}" }
                _events.tryEmit(PostDetailEvent.ShowSnackbar(e.message))
            }
        }
    }

    // -------------------- REACTIONS --------------------

    fun togglePostReaction(emoji: String) {
        val post = uiState.value.post ?: return
        viewModelScope.launch {
            try {
                reactionService.toggleReaction(post, emoji)
            } catch (e: Exception) {
                Logger.e(throwable = e, tag = TAG) { "togglePostReaction failed: ${e.message}" }
                _events.tryEmit(PostDetailEvent.ShowSnackbar(e.message))
            }
        }
    }

    fun toggleCommentReaction(comment: PostCommentItem, emoji: String) {
        viewModelScope.launch {
            try {
                reactionService.toggleReaction(comment, emoji)
            } catch (e: Exception) {
                Logger.e(throwable = e, tag = TAG) { "toggleCommentReaction failed: ${e.message}" }
                _events.tryEmit(PostDetailEvent.ShowSnackbar(e.message))
            }
        }
    }

    // -------------------- POST --------------------

    /**
     * Delete the whole post. The post's own [FeedPostItem.driveId] is the drive the file
     * lives on (= the channel-drive alias the sender writes to), so it's the correct
     * `channelId` for [FeedPostSenderService.deletePost]. The optimistic writer removes the
     * post from the feed before this returns, so we pop back immediately — the user lands
     * on the feed with the post already gone rather than on a flashing empty detail.
     */
    fun deletePost() {
        val post = uiState.value.post ?: return
        viewModelScope.launch {
            try {
                senderService.deletePost(channelId = post.driveId, postUniqueId = post.id)
                _events.tryEmit(PostDetailEvent.NavigateBack)
            } catch (e: Exception) {
                Logger.e(throwable = e, tag = TAG) { "deletePost failed: ${e.message}" }
                _events.tryEmit(PostDetailEvent.ShowSnackbar(e.message))
            }
        }
    }

    fun navigateToAuthor() {
        val author: OdinId = uiState.value.post?.let { it.originalAuthor ?: it.senderOdinId }
            ?: return
        _events.tryEmit(PostDetailEvent.NavigateToAuthor(author))
    }
}

/** One-time navigation / snackbar events for the post detail screen. */
sealed interface PostDetailEvent {
    data object NavigateBack : PostDetailEvent
    data class ShowSnackbar(val message: String?) : PostDetailEvent
    data class NavigateToAuthor(val odinId: OdinId) : PostDetailEvent
}
