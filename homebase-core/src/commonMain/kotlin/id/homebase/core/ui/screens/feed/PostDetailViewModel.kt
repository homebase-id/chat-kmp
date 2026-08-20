package id.homebase.core.ui.screens.feed

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.drives.files.ReactionSummary
import id.homebase.api.client.profile.PublicProfileProviderCached
import id.homebase.api.common.OdinId
import id.homebase.chat.services.builder.AttachmentInput
import id.homebase.chat.services.convo.contact.ContactService
import id.homebase.chat.services.sticker.StickerStream
import id.homebase.core.feed.services.FeedPostItem
import id.homebase.core.feed.services.FeedPostSenderService
import id.homebase.core.feed.services.FeedTimelineService
import id.homebase.core.feed.services.PostCommentItem
import id.homebase.core.feed.services.PostCommentsService
import id.homebase.core.feed.services.CanReact
import id.homebase.core.feed.services.FeedPermissionService
import id.homebase.core.feed.services.PostReactionService
import id.homebase.core.feed.services.ReportingUrlProvider
import id.homebase.core.feed.services.authorOdinId
import id.homebase.core.feed.services.emojiCounts
import id.homebase.core.feed.services.isAuthoredBy
import id.homebase.core.feed.services.withOwnReactions
import id.homebase.core.widget.ReactionDisplayItem
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

private const val TAG = "PostDetailViewModel"

// Sourced from the live timeline, not a DB re-query, so an edit replaying from another device re-renders for free.
class PostDetailViewModel(
    private val postId: Uuid,
    private val timelineService: FeedTimelineService,
    private val commentsService: PostCommentsService,
    private val reactionService: PostReactionService,
    private val senderService: FeedPostSenderService,
    private val credentialsManager: CredentialsManager,
    private val contactService: ContactService,
    private val stickerStream: StickerStream,
    private val publicProfileProvider: PublicProfileProviderCached,
    private val reportingUrlProvider: ReportingUrlProvider,
    private val permissionService: FeedPermissionService,
) : ViewModel() {

    private val _selfOdinId = MutableStateFlow<OdinId?>(null)
    private val _selfName = MutableStateFlow<String?>(null)
    private val _replyingTo = MutableStateFlow<PostCommentItem?>(null)

    // Our own posts only: the group-reactions endpoint targets our own identity, so on a followed
    // post it reads our feed-drive copy and undercounts. Someone else's post trusts the header.
    private val _liveReactionSummary = MutableStateFlow<ReactionSummary?>(null)

    private val _canReact = MutableStateFlow<CanReact?>(null)

    // localAppData.localReactions is written only by the local optimistic writer and is null on every post header.
    private val _ownReactions = MutableStateFlow<List<String>?>(null)

    // Folded so the aux combine below stays on the typed 5-arg overload.
    private val _postAux = combine(_liveReactionSummary, _canReact, _ownReactions, ::Triple)

    // list == null means the sheet is closed; non-null (possibly empty) means open.
    private val _reactors = MutableStateFlow(ReactorsState())

    private val _events = MutableSharedFlow<PostDetailEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<PostDetailEvent> = _events.asSharedFlow()

    // A null post BEFORE the first timeline emission is "still loading"; after it, "not found".
    private val _timelineEmitted = MutableStateFlow(false)

    init {
        timelineService.start()
        // Idempotent — hydrate contact/connection streams so author names resolve here too.
        contactService.start()
        // The sticker tray's StickerStream preload is dropped on session-restore launches (an
        // AuthConnectionCoordinator race); start() is idempotent, so calling it here keeps the tray working.
        stickerStream.start()
        // Reload whenever a sync batch bumps the header's reaction preview or replaces the file.
        viewModelScope.launch {
            timelineService.timeline
                .mapNotNull { feed -> feed.firstOrNull { it.id == postId } }
                .distinctUntilChanged { a, b ->
                    a.fileId == b.fileId && a.reactionPreview == b.reactionPreview
                }
                .collect { post -> loadLiveReactions(post) }
        }
        // Keyed on fileId so it runs once per post: re-reading on every header bump would race the
        // outbox send and flick the heart back off before it lands.
        viewModelScope.launch {
            timelineService.timeline
                .mapNotNull { feed -> feed.firstOrNull { it.id == postId } }
                .distinctUntilChangedBy { it.fileId }
                .collect { post ->
                    // Nobody reacted ⇒ neither did we; never pay for a roster read on a bare post.
                    if (post.reactionPreview?.reactions.isNullOrEmpty()) {
                        _ownReactions.value = emptyList()
                        return@collect
                    }
                    _ownReactions.value = runCatching { reactionService.ownReactions(post) }
                        .onFailure {
                            Logger.w(throwable = it, tag = TAG) {
                                "ownReactions read failed for post=$postId: ${it.message}"
                            }
                        }
                        .getOrNull()
                }
        }
        viewModelScope.launch {
            timelineService.timeline
                .mapNotNull { feed -> feed.firstOrNull { it.id == postId } }
                .distinctUntilChangedBy {
                    listOf(it.driveId, it.channelId, it.authorOdinId, it.reactAccess)
                }
                .collect { post -> _canReact.value = permissionService.canReact(post) }
        }
        viewModelScope.launch {
            val self = credentialsManager.getActiveCredentials()?.domain
            _selfOdinId.value = self
                // You aren't in your own ContactService contacts, so without this your own posts show a raw domain.
            if (self != null) {
                _selfName.value =
                    runCatching { publicProfileProvider.getPublicProfile(self)?.name }.getOrNull()
            }
        }
    }

    // Folded so the main combine stays on the typed 5-arg overload.
    private val _detailAux = combine(
        _selfOdinId,
        _reactors,
        contactService.contacts,
        _selfName,
        _postAux,
    ) { self, reactors, contacts, selfName, (liveReactions, canReact, ownReactions) ->
        val names = contacts.associate { it.odinId to it.name }.toMutableMap()
        if (self != null && !selfName.isNullOrBlank()) names[self] = selfName
        DetailAux(self, reactors, names.toMap(), liveReactions, canReact, ownReactions)
    }

    // Needs the resolved post, not just [postId]: routing a peer comment read wants author/channel/globalTransitId.
    @OptIn(ExperimentalCoroutinesApi::class)
    private val _comments: Flow<List<PostCommentItem>> = timelineService.timeline
        .mapNotNull { feed -> feed.firstOrNull { it.id == postId } }
        .distinctUntilChangedBy {
            listOf(it.id, it.senderOdinId, it.globalTransitId, it.channelId)
        }
        .flatMapLatest { post -> commentsService.commentsFor(post) }
        .onStart { emit(emptyList()) }

    val uiState: StateFlow<PostDetailUiState> = combine(
        timelineService.timeline
            .onEach { _timelineEmitted.value = true }
            .map { feed -> feed.firstOrNull { it.id == postId } },
        _comments,
        _replyingTo,
        _detailAux,
        _timelineEmitted,
    ) { postRaw, comments, replyingTo, aux, timelineEmitted ->
        // Live tallies are trustworthy on OUR OWN post only — see [_liveReactionSummary]. A feed
        // reference's header preview is kept accurate by the author's distribution, so it wins elsewhere.
        val useLiveReactions = postRaw != null && postRaw.isAuthoredBy(aux.self)
        val post = postRaw?.copy(
            reactionPreview = aux.liveReactions
                ?.takeIf { useLiveReactions && it.reactions.isNotEmpty() }
                ?: postRaw.reactionPreview,
            commentCount = comments.size,
        )?.withOwnReactions(aux.ownReactions)
        PostDetailUiState(
            post = post,
            comments = comments,
                // Seeded false so a WhileSubscribed re-subscription never flashes the spinner over loaded content.
            isLoading = !timelineEmitted && post == null,
            replyingTo = replyingTo,
            selfOdinId = aux.self,
            displayNames = aux.displayNames,
            reactorsSheet = aux.reactors.list,
            isReactorsLoading = aux.reactors.loading,
            reactorsCounts = aux.reactors.counts,
            reactorsPartial = aux.reactors.partial,
            canReact = aux.canReact,
            errorMessage = null,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        PostDetailUiState(),
    )


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


    // Local flip: the header tally only moves once the author redistributes, so nothing else would light this up.
    fun togglePostReaction(emoji: String) {
        val post = uiState.value.post ?: return
        flipOwnReaction(emoji)
        viewModelScope.launch {
            try {
                reactionService.toggleReaction(post, emoji)
            } catch (e: Exception) {
                Logger.e(throwable = e, tag = TAG) { "togglePostReaction failed: ${e.message}" }
                flipOwnReaction(emoji)
                // Null, not e.message: a rejected enqueue throws with internal text; the screen localizes null.
                _events.tryEmit(PostDetailEvent.ShowSnackbar(null))
            }
        }
    }

    private fun flipOwnReaction(emoji: String) = _ownReactions.update { current ->
        val held = current.orEmpty()
        if (emoji in held) held - emoji else held + emoji
    }

    fun toggleCommentReaction(comment: PostCommentItem, emoji: String) {
        viewModelScope.launch {
            try {
                reactionService.toggleReaction(comment, emoji)
            } catch (e: Exception) {
                Logger.e(throwable = e, tag = TAG) { "toggleCommentReaction failed: ${e.message}" }
                _events.tryEmit(PostDetailEvent.ShowSnackbar(null))
            }
        }
    }

    private fun loadLiveReactions(post: FeedPostItem) {
        viewModelScope.launch {
            runCatching { reactionService.liveReactionSummary(post) }
                .onSuccess { _liveReactionSummary.value = it }
                .onFailure {
                    Logger.w(throwable = it, tag = TAG) { "loadLiveReactions failed: ${it.message}" }
                }
        }
    }

    fun showReactors() {
        val post = uiState.value.post ?: return
        // The roster only sees our own identity's rows on a followed post, so it is flagged partial.
        val opened = ReactorsState(
            list = emptyList(),
            loading = true,
            counts = post.reactionPreview.emojiCounts(),
            partial = !post.isAuthoredBy(_selfOdinId.value),
        )
        _reactors.value = opened
        viewModelScope.launch {
            try {
                val reactors = reactionService.listReactors(post, null).map {
                    ReactionDisplayItem(
                        odinId = it.odinId.domainName,
                        displayName = contactService.resolveByOdinId(it.odinId).name
                            .ifBlank { it.odinId.domainName },
                        emoji = it.emoji,
                    )
                }
                // Drop if the user dismissed the sheet while the fetch was in flight.
                if (_reactors.value.list != null) {
                    _reactors.value = opened.copy(list = reactors, loading = false)
                }
            } catch (e: Exception) {
                Logger.e(throwable = e, tag = TAG) { "showReactors failed: ${e.message}" }
                _reactors.value = ReactorsState()
                _events.tryEmit(PostDetailEvent.ShowSnackbar(e.message))
            }
        }
    }

    fun dismissReactors() {
        _reactors.value = ReactorsState()
    }


    // [FeedPostItem.driveId] is the channel-drive alias the sender wrote to — the correct channelId to delete against.
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
        val author: OdinId = uiState.value.post?.authorOdinId ?: return
        _events.tryEmit(PostDetailEvent.NavigateToAuthor(author))
    }

    // The destination is the author's own config/reporting endpoint, so resolving it is a network call.
    fun reportPost() {
        val author = uiState.value.post?.authorOdinId ?: return
        viewModelScope.launch {
            _events.tryEmit(PostDetailEvent.OpenUrl(reportingUrlProvider.reportUrlFor(author)))
        }
    }
}

// [list] null == closed, non-null (possibly empty) == open.
private data class ReactorsState(
    val list: List<ReactionDisplayItem>? = null,
    val loading: Boolean = false,
    /** Authoritative per-emoji tallies off the post header; the roster can't be trusted for these. */
    val counts: Map<String, Int> = emptyMap(),
    /** The roster is knowably incomplete — the post is hosted by another identity. */
    val partial: Boolean = false,
)

private data class DetailAux(
    val self: OdinId?,
    val reactors: ReactorsState,
    val displayNames: Map<OdinId, String>,
    val liveReactions: ReactionSummary?,
    val canReact: CanReact?,
    val ownReactions: List<String>?,
)

sealed interface PostDetailEvent {
    data object NavigateBack : PostDetailEvent
    data class ShowSnackbar(val message: String?) : PostDetailEvent
    data class NavigateToAuthor(val odinId: OdinId) : PostDetailEvent

    /** Hand off to the browser — abuse reporting lives on the author's host, not in-app. */
    data class OpenUrl(val url: String) : PostDetailEvent
}
