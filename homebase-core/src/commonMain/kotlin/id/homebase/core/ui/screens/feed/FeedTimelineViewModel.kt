package id.homebase.core.ui.screens.feed

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.profile.PublicProfileProviderCached
import id.homebase.api.common.OdinId
import id.homebase.chat.conversationlist.ExtendPermissionViewModel
import id.homebase.chat.services.convo.contact.ContactService
import id.homebase.core.config.feedLabeledDrive
import id.homebase.core.config.publicChannelLabeledDrive
import id.homebase.core.feed.services.ChannelDefinitionService
import id.homebase.core.feed.services.ChannelDefinition
import id.homebase.core.feed.services.FeedPostItem
import id.homebase.core.feed.services.FeedPostSenderService
import id.homebase.core.feed.services.FeedProtocol
import id.homebase.core.feed.services.FeedTimelineService
import id.homebase.core.feed.services.PostOwnReactionResolver
import id.homebase.core.feed.services.PostReactionService
import id.homebase.core.feed.services.ReportingUrlProvider
import id.homebase.core.feed.services.authorOdinId
import id.homebase.core.feed.services.emojiCounts
import id.homebase.core.feed.services.isAuthoredBy
import id.homebase.core.feed.services.withOwnReactions
import id.homebase.core.sync.OptionalDriveActivation
import id.homebase.core.widget.ReactionDisplayItem
import id.homebase.resources.MR
import id.homebase.resources.feed_post_delete_failed
import id.homebase.resources.feed_reaction_failed
import id.homebase.resources.feed_timeline_refresh_failed
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import kotlin.uuid.Uuid

// isLoading is seeded directly in the MutableStateFlow, not via a stateIn(WhileSubscribed) default, so the
// spinner doesn't flash back on every re-subscription.
// The two source drives are grant-gated add-ons, not login drives: they mount through OptionalDriveActivation.
class FeedTimelineViewModel(
    private val timelineService: FeedTimelineService,
    private val reactionService: PostReactionService,
    private val channelService: ChannelDefinitionService,
    private val contactService: ContactService,
    private val credentialsManager: CredentialsManager,
    private val publicProfileProvider: PublicProfileProviderCached,
    private val senderService: FeedPostSenderService,
    private val reportingUrlProvider: ReportingUrlProvider,
    private val feedPermissionViewModel: ExtendPermissionViewModel,
    private val optionalDriveActivation: OptionalDriveActivation,
) : ViewModel() {

    val extendPermissionViewModel: ExtendPermissionViewModel
        get() = feedPermissionViewModel

    private val _uiState = MutableStateFlow(FeedTimelineUiState(isLoading = true))
    val uiState: StateFlow<FeedTimelineUiState> = _uiState.asStateFlow()

    val channels: StateFlow<Map<String, ChannelDefinition>> = channelService.channels

    // Only known identities appear here; the screen falls back to the raw domain, mirroring the web feed.
    // You aren't in your own ContactService contacts, so your own posts need this to show a name not a domain.
    private val _selfName = MutableStateFlow<Pair<OdinId, String>?>(null)

    val displayNames: StateFlow<Map<OdinId, String>> =
        combine(contactService.contacts, _selfName) { contacts, self ->
            val names = contacts.associate { it.odinId to it.name }.toMutableMap()
            if (self != null) names[self.first] = self.second
            names.toMap()
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    // Public posts (blank id or the public alias) are never labelled.
    fun channelNameFor(channelId: String): String? =
        if (isPublicChannel(channelId)) {
            null
        } else {
            channelService.nameFor(channelId)
        }

    fun isPublicChannel(channelId: String): Boolean =
        channelId.isBlank() || channelId == FeedProtocol.PublicChannelDriveAlias.toString()

    private val _events = MutableSharedFlow<FeedTimelineEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<FeedTimelineEvent> = _events.asSharedFlow()

    private val ownReactionResolver = PostOwnReactionResolver(reactionService)

    private var rawPosts: List<FeedPostItem> = emptyList()

    private var reactionWindow = REACTION_WINDOW

    // One-shot guard: the drives only read as mounted once activate()'s mount lands, so a second grant
    // emission in that window would re-enter. Same latch MomentsViewModel uses.
    private var activationKicked = false

    companion object {
        private const val TAG = "FeedTimelineViewModel"

        private const val REACTION_WINDOW = 20
    }

    init {
        // Idempotent. AppModule.onPostAuthenticated normally starts the service, but that hook never fires on
        // a session-restore launch, leaving a returning user with an empty feed until a full relogin.
        timelineService.start()
        // Idempotent — hydrates contact/connection streams so author names resolve on a session-restore launch.
        contactService.start()
        viewModelScope.launch {
            val self = credentialsManager.getActiveCredentials()?.domain ?: return@launch
            _uiState.update { it.copy(selfOdinId = self) }
            val name = runCatching { publicProfileProvider.getPublicProfile(self)?.name }.getOrNull()
            if (!name.isNullOrBlank()) _selfName.value = self to name
        }
        viewModelScope.launch {
            timelineService.timeline.collect { posts ->
                rawPosts = posts
                _uiState.update {
                    it.copy(
                        posts = overlayOwnReactions(posts),
                        isLoading = false,
                        isRefreshing = false,
                    )
                }
                // Off the collector: a roster read must not hold up the next timeline emission.
                launch { ownReactionResolver.resolve(posts, reactionWindow) }
            }
        }
        viewModelScope.launch {
            ownReactionResolver.ownReactions.collect {
                _uiState.update { it.copy(posts = overlayOwnReactions(rawPosts)) }
            }
        }
        viewModelScope.launch {
            timelineService.endReached.collect { reached ->
                _uiState.update { it.copy(endReached = reached) }
            }
        }
        viewModelScope.launch {
            timelineService.loadError.collect { error ->
                _uiState.update {
                    if (error == null) {
                        it.copy(errorMessage = null)
                    } else {
                        // The timeline never emits on a failed load, so nothing else takes the skeletons down.
                        it.copy(errorMessage = error, isLoading = false, isRefreshing = false)
                    }
                }
                // A full-screen error would blank a working feed, so with posts on screen it stays a snackbar.
                if (error != null && rawPosts.isNotEmpty()) {
                    _events.tryEmit(
                        FeedTimelineEvent.ShowSnackbar(MR.string.feed_timeline_refresh_failed)
                    )
                }
            }
        }
        viewModelScope.launch {
            feedPermissionViewModel.permissionsGranted
                .filter { it }
                .collect {
                    if (!activationKicked && !feedDrivesMounted()) {
                        activationKicked = true
                        // One extend-permissions flow grants both source drives.
                        optionalDriveActivation.activate(feedLabeledDrive)
                        optionalDriveActivation.activate(publicChannelLabeledDrive)
                    }
                }
        }
    }

    private fun feedDrivesMounted(): Boolean =
        optionalDriveActivation.isActivated(feedLabeledDrive) &&
            optionalDriveActivation.isActivated(publicChannelLabeledDrive)

    private fun overlayOwnReactions(posts: List<FeedPostItem>): List<FeedPostItem> {
        val resolved = ownReactionResolver.ownReactions.value
        return posts.map { it.withOwnReactions(resolved[it.fileId]) }
    }

    // The service drops a call made while a page fetch is already in flight, so the scroll trigger can't stack.
    fun loadMore() {
        // Resolving here as well as on the next timeline emission matters once the feed is fully paged: no
        // further emission would come to trigger it.
        val loaded = rawPosts
        reactionWindow = loaded.size + REACTION_WINDOW
        viewModelScope.launch { ownReactionResolver.resolve(loaded, reactionWindow) }
        if (_uiState.value.endReached) return
        viewModelScope.launch {
            try {
                timelineService.loadMore()
            } catch (t: Throwable) {
                Logger.e(throwable = t, tag = TAG) { "loadMore failed: ${t.message}" }
            }
        }
    }

    // isRefreshing is held true until refresh() returns and cleared in finally, so neither a failure nor an
    // early timeline re-emit can strand the indicator.
    fun refresh() {
        _uiState.update { it.copy(isRefreshing = true) }
        viewModelScope.launch {
            try {
                timelineService.refresh()
            } catch (t: Throwable) {
                Logger.e(throwable = t, tag = TAG) { "refresh failed: ${t.message}" }
            } finally {
                _uiState.update { it.copy(isRefreshing = false) }
            }
        }
    }

    fun onPostClick(postId: Uuid) {
        _events.tryEmit(FeedTimelineEvent.NavigateToDetail(postId))
    }

    // The own-reaction flip is applied against the RAW timeline item: on a followed post the optimistic write
    // can't run (no uniqueId) and the tally only moves once the author redistributes, so nothing else would
    // light the like button up.
    fun onToggleReaction(post: FeedPostItem, emoji: String) {
        val raw = rawPosts.firstOrNull { it.id == post.id } ?: post
        ownReactionResolver.applyLocalToggle(raw, emoji)
        viewModelScope.launch {
            try {
                reactionService.toggleReaction(raw, emoji)
            } catch (t: Throwable) {
                Logger.e(throwable = t, tag = TAG) {
                    "toggleReaction failed for post=${post.id} emoji=$emoji: ${t.message}"
                }
                ownReactionResolver.applyLocalToggle(raw, emoji)
                _events.tryEmit(FeedTimelineEvent.ShowSnackbar(MR.string.feed_reaction_failed))
            }
        }
    }

    // Chips are labelled from the post header, not the roster: on someone else's post the roster read only
    // sees our own rows, so it is flagged partial.
    fun showReactors(post: FeedPostItem) {
        _uiState.update {
            it.copy(
                reactorsSheet = emptyList(),
                isReactorsLoading = true,
                reactorsCounts = post.reactionPreview.emojiCounts(),
                reactorsPartial = !post.isAuthoredBy(it.selfOdinId),
            )
        }
        viewModelScope.launch {
            try {
                val reactors = reactionService.listReactors(post, null).map {
                    ReactionDisplayItem(
                        odinId = it.odinId.domainName,
                        displayName = contactService.resolveByOdinId(it.odinId)?.name
                            ?: it.odinId.domainName,
                        emoji = it.emoji,
                    )
                }
                _uiState.update {
                    if (it.reactorsSheet != null) {
                        it.copy(reactorsSheet = reactors, isReactorsLoading = false)
                    } else {
                        it
                    }
                }
            } catch (t: Throwable) {
                Logger.e(throwable = t, tag = TAG) {
                    "showReactors failed for post=${post.id}: ${t.message}"
                }
                _uiState.update { it.copy(reactorsSheet = null, isReactorsLoading = false) }
            }
        }
    }

    fun dismissReactors() {
        _uiState.update {
            it.copy(
                reactorsSheet = null,
                isReactorsLoading = false,
                reactorsCounts = emptyMap(),
                reactorsPartial = false,
            )
        }
    }

    // [FeedPostItem.driveId] is the channel drive the post lives on — the correct channelId to delete against.
    fun deletePost(post: FeedPostItem) {
        viewModelScope.launch {
            try {
                senderService.deletePost(channelId = post.driveId, postUniqueId = post.id)
            } catch (t: Throwable) {
                Logger.e(throwable = t, tag = TAG) { "deletePost failed for post=${post.id}: ${t.message}" }
                _events.tryEmit(FeedTimelineEvent.ShowSnackbar(MR.string.feed_post_delete_failed))
            }
        }
    }

    // The destination is the author's own config/reporting endpoint, so resolving it is a network call.
    fun reportPost(post: FeedPostItem) {
        val author = post.authorOdinId ?: return
        viewModelScope.launch {
            _events.tryEmit(FeedTimelineEvent.OpenUrl(reportingUrlProvider.reportUrlFor(author)))
        }
    }
}

sealed interface FeedTimelineEvent {
    data class NavigateToDetail(val postId: Uuid) : FeedTimelineEvent
    data class ShowSnackbar(val messageKey: StringResource) : FeedTimelineEvent

    /** Hand off to the browser — abuse reporting lives on the author's host, not in-app. */
    data class OpenUrl(val url: String) : FeedTimelineEvent
}
