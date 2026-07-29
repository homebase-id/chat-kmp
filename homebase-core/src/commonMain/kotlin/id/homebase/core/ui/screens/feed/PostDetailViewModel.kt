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
    private val contactService: ContactService,
    private val stickerStream: StickerStream,
    private val publicProfileProvider: PublicProfileProviderCached,
    private val reportingUrlProvider: ReportingUrlProvider,
    private val permissionService: FeedPermissionService,
) : ViewModel() {

    private val _selfOdinId = MutableStateFlow<OdinId?>(null)
    private val _selfName = MutableStateFlow<String?>(null)
    private val _replyingTo = MutableStateFlow<PostCommentItem?>(null)

    /**
     * Live reaction tallies for the open post, read fresh via
     * [PostReactionService.liveReactionSummary] (the same group-reactions path chat uses).
     *
     * Applied to **our own posts only**. That endpoint targets our own identity, so on a followed
     * identity's post it reads our feed-drive copy — which holds only reactions we ourselves sent
     * — and would undercount. A feed reference's header preview is kept accurate by the author's
     * distribution, so that stays the source of truth for someone else's post.
     */
    private val _liveReactionSummary = MutableStateFlow<ReactionSummary?>(null)

    /** Null until the first permission resolve lands — the UI renders no verdict before then. */
    private val _canReact = MutableStateFlow<CanReact?>(null)

    /**
     * The bare glyphs WE hold on the open post; null until the first roster read lands.
     *
     * `localAppData.localReactions` — the field [FeedPostItem.ownReactions] is parsed from — is
     * written only by the local optimistic writer and is null on every post header, so without this
     * read the like button on the detail screen could never render active. The timeline solves the
     * same problem with [id.homebase.core.feed.services.PostOwnReactionResolver]; a single post
     * needs only one read, so it is done inline here.
     */
    private val _ownReactions = MutableStateFlow<List<String>?>(null)

    // Folded so the aux combine below stays on the typed 5-arg overload.
    private val _postAux = combine(_liveReactionSummary, _canReact, _ownReactions, ::Triple)

    /**
     * Reactor roster for the "who reacted" sheet. `list == null` means the sheet is
     * closed; a non-null list (possibly empty) means it's open. [ReactorsState.loading]
     * covers the in-flight [PostReactionService.listReactors] fetch before the list lands.
     */
    private val _reactors = MutableStateFlow(ReactorsState())

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
        // Idempotent — hydrate contact/connection streams so author names resolve here too.
        contactService.start()
        // INTERIM consumer-side start, mirroring timeline/contacts above: the comment composer's
        // sticker tray reads StickerStream, which is cold-loaded by onPostAuthenticated. That hook
        // is silently DROPPED on a session-restore launch (a race in AuthConnectionCoordinator:
        // promoteToForeground() flips out of headless mid-bootstrap, before lastAuthenticatedDrives
        // is set, so neither it nor the Authenticated branch runs the preload), leaving the tray
        // spinning forever. start() is idempotent, so calling it here keeps the tray working.
        // FOLLOW-UP: fix the race at the source in AuthConnectionCoordinator (run onPostAuthenticated
        // exactly once even when promoted mid-bootstrap) and drop these consumer-side start() calls.
        stickerStream.start()
        // Live-load the post's reactions the way chat does (fresh group-reactions read). Only used
        // for our own posts — see [_liveReactionSummary]. Reload whenever a sync batch bumps the
        // header's reaction preview (someone reacted) or the file is replaced.
        viewModelScope.launch {
            timelineService.timeline
                .mapNotNull { feed -> feed.firstOrNull { it.id == postId } }
                .distinctUntilChanged { a, b ->
                    a.fileId == b.fileId && a.reactionPreview == b.reactionPreview
                }
                .collect { post -> loadLiveReactions(post) }
        }
        // Which of the post's reactions are OURS. Keyed on fileId, so it runs once per post: our own
        // reactions only change by our own action, which [togglePostReaction] already applies
        // locally — re-reading on every header bump would race the outbox send and flick the heart
        // back off before it lands.
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
        // Whether we may react/comment depends on the grants the post's channel carries, so it is
        // re-resolved whenever the post's routing or its author's interaction setting changes.
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
            // Resolve the owner's OWN display name from their public profile — you aren't in your
            // own ContactService contacts, so without this your posts/comments show your raw domain.
            if (self != null) {
                _selfName.value =
                    runCatching { publicProfileProvider.getPublicProfile(self)?.name }.getOrNull()
            }
        }
    }

    // Fold self-identity, the reactor-sheet state, the resolved-name map, and the live reaction
    // summary into one upstream so the main combine stays at the typed 5-arg overload.
    private val _detailAux = combine(
        _selfOdinId,
        _reactors,
        contactService.contacts,
        _selfName,
        _postAux,
    ) { self, reactors, contacts, selfName, (liveReactions, canReact, ownReactions) ->
        val names = contacts.associate { it.odinId to it.name }.toMutableMap()
        // Overlay the owner's own resolved name so your posts/comments show your name, not domain.
        if (self != null && !selfName.isNullOrBlank()) names[self] = selfName
        DetailAux(self, reactors, names.toMap(), liveReactions, canReact, ownReactions)
    }

    /**
     * The post's comment thread, sourced from the resolved post so a followed/received post can be
     * cold-loaded over peer (comments live on the author's drive). We can't call `commentsFor` with
     * just [postId] because it needs the post's author/channel/globalTransitId to route the peer
     * read; those only exist on the resolved [FeedPostItem]. Re-subscribes only when the routing
     * fields change (effectively once), and seeds an empty list so the main combine can emit before
     * the post resolves.
     */
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
        // The live read (chat parity) is only trustworthy on OUR OWN post: the group-reactions
        // endpoint targets our own identity, so on a followed post it reads our feed-drive copy,
        // which holds only reactions we sent — it can only undercount (a post with two hearts
        // renders one once we've reacted). The author's distribution keeps the header preview on a
        // feed reference accurate — spot-checked 18/18 against the authors' own servers — so for
        // someone else's post the header wins. Comment count reflects the thread rendered below.
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
            // Loading only until the first timeline emission lands. A null post AFTER
            // that means the post isn't on either source drive — not "still loading" —
            // so the spinner doesn't spin forever. Seeded false so a WhileSubscribed
            // re-subscription never flashes the spinner over loaded content.
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

    /**
     * The own-reaction flip is local: the send goes through the outbox and the header tally only
     * moves once the author redistributes it, so nothing else would light the like button up on a
     * followed post. A failed toggle flips it straight back.
     */
    fun togglePostReaction(emoji: String) {
        val post = uiState.value.post ?: return
        flipOwnReaction(emoji)
        viewModelScope.launch {
            try {
                reactionService.toggleReaction(post, emoji)
            } catch (e: Exception) {
                Logger.e(throwable = e, tag = TAG) { "togglePostReaction failed: ${e.message}" }
                flipOwnReaction(emoji)
                // Null, not e.message: a rejected enqueue throws with internal text
                // ("outbox enqueue -> Failed(..)"), and the screen renders null as its
                // localized fallback.
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

    /** Fetch the post's live reaction tallies (chat-parity) and publish them to the UI. */
    private fun loadLiveReactions(post: FeedPostItem) {
        viewModelScope.launch {
            runCatching { reactionService.liveReactionSummary(post) }
                .onSuccess { _liveReactionSummary.value = it }
                .onFailure {
                    Logger.w(throwable = it, tag = TAG) { "loadLiveReactions failed: ${it.message}" }
                }
        }
    }

    /**
     * Open the "who reacted" sheet for the post and fetch its reactor roster. Opens with
     * an empty list + loading flag so the sheet appears immediately, then fills in once
     * [PostReactionService.listReactors] returns. Reactor names resolve through
     * [ContactService] (falling back to the raw domain), and the sheet's avatar is derived
     * from the odinId.
     */
    fun showReactors() {
        val post = uiState.value.post ?: return
        // Chips are labelled from the header, which stays correct on a followed post; the roster
        // itself only sees our own identity's rows there, so it is flagged partial.
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
        val author: OdinId = uiState.value.post?.authorOdinId ?: return
        _events.tryEmit(PostDetailEvent.NavigateToAuthor(author))
    }

    /**
     * Report the post to whoever hosts its author. The destination is the author's own
     * `config/reporting` endpoint (with a shared fallback) — resolving it is a network call,
     * so this runs in the VM and the screen just opens whatever URL comes back.
     */
    fun reportPost() {
        val author = uiState.value.post?.authorOdinId ?: return
        viewModelScope.launch {
            _events.tryEmit(PostDetailEvent.OpenUrl(reportingUrlProvider.reportUrlFor(author)))
        }
    }
}

/**
 * Reactor-sheet state: [list] null == sheet closed, non-null (possibly empty) == open.
 * [loading] is the in-flight fetch flag while the roster is being loaded.
 */
private data class ReactorsState(
    val list: List<ReactionDisplayItem>? = null,
    val loading: Boolean = false,
    /** Authoritative per-emoji tallies off the post header; the roster can't be trusted for these. */
    val counts: Map<String, Int> = emptyMap(),
    /** The roster is knowably incomplete — the post is hosted by another identity. */
    val partial: Boolean = false,
)

/**
 * The detail's auxiliary streams (self identity, reactor sheet, resolved names, live reaction
 * summary) folded into one flow so the main [PostDetailViewModel.uiState] combine stays within the
 * typed 5-argument overload.
 */
private data class DetailAux(
    val self: OdinId?,
    val reactors: ReactorsState,
    val displayNames: Map<OdinId, String>,
    val liveReactions: ReactionSummary?,
    val canReact: CanReact?,
    val ownReactions: List<String>?,
)

/** One-time navigation / snackbar events for the post detail screen. */
sealed interface PostDetailEvent {
    data object NavigateBack : PostDetailEvent
    data class ShowSnackbar(val message: String?) : PostDetailEvent
    data class NavigateToAuthor(val odinId: OdinId) : PostDetailEvent

    /** Hand off to the browser — abuse reporting lives on the author's host, not in-app. */
    data class OpenUrl(val url: String) : PostDetailEvent
}
