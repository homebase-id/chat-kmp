package id.homebase.core.ui.screens.feed

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.profile.PublicProfileProviderCached
import id.homebase.api.common.OdinId
import id.homebase.chat.services.convo.contact.ContactService
import id.homebase.core.feed.services.ChannelDefinitionService
import id.homebase.core.feed.services.ChannelDefinition
import id.homebase.core.feed.services.FeedPostItem
import id.homebase.core.feed.services.FeedProtocol
import id.homebase.core.feed.services.FeedTimelineService
import id.homebase.core.feed.services.PostReactionService
import id.homebase.resources.MR
import id.homebase.resources.feed_reaction_failed
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import kotlin.uuid.Uuid

/**
 * Drives the native home timeline ([FeedTimelineScreen]).
 *
 * [FeedTimelineService] is an app-scoped singleton normally started by
 * `AppModule.onPostAuthenticated`; this VM also calls its idempotent [start] in `init`
 * so a session-restore launch (where that preload hook never fires) still loads the
 * feed without a relogin. The first emission flips [FeedTimelineUiState.isLoading]
 * false; we deliberately seed `isLoading = true` directly in the [MutableStateFlow] (not via
 * a `stateIn(WhileSubscribed)` default) so the spinner doesn't flash back on every
 * re-subscription (see the MomentDetail spinner bug in CLAUDE.md).
 *
 * Reactions go through [PostReactionService.toggleReaction], whose optimistic write updates
 * the underlying file's reaction preview; the service re-emits the timeline so the card
 * re-renders with the new tally — no local mutation of [FeedTimelineUiState.posts] needed.
 */
class FeedTimelineViewModel(
    private val timelineService: FeedTimelineService,
    private val reactionService: PostReactionService,
    private val channelService: ChannelDefinitionService,
    private val contactService: ContactService,
    private val credentialsManager: CredentialsManager,
    private val publicProfileProvider: PublicProfileProviderCached,
) : ViewModel() {

    private val _uiState = MutableStateFlow(FeedTimelineUiState(isLoading = true))
    val uiState: StateFlow<FeedTimelineUiState> = _uiState.asStateFlow()

    /** `channelId → [ChannelDefinition]` so the screen can label a post's (non-public) channel. */
    val channels: StateFlow<Map<String, ChannelDefinition>> = channelService.channels

    /**
     * Reactive `odinId → resolved display name`, sourced from [ContactService] (saved contacts
     * merged with connections). Only known identities appear here; the screen falls back to the
     * raw domain for anyone absent — mirroring the web feed's `AuthorName` (`fullName ?? odinId`).
     */
    /** Owner's own resolved name (public profile), overlaid so your own posts show your name not
     *  your raw domain — you aren't in your own ContactService contacts. */
    private val _selfName = MutableStateFlow<Pair<OdinId, String>?>(null)

    val displayNames: StateFlow<Map<OdinId, String>> =
        combine(contactService.contacts, _selfName) { contacts, self ->
            val names = contacts.associate { it.odinId to it.name }.toMutableMap()
            if (self != null) names[self.first] = self.second
            names.toMap()
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /**
     * Channel name to show on a post, or null for a public/unknown channel (which shows no label).
     * Public posts (blank id or the public alias) are never labelled.
     */
    fun channelNameFor(channelId: String): String? =
        if (channelId.isBlank() || channelId == FeedProtocol.PublicChannelDriveAlias.toString()) {
            null
        } else {
            channelService.nameFor(channelId)
        }

    private val _events = MutableSharedFlow<FeedTimelineEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<FeedTimelineEvent> = _events.asSharedFlow()

    companion object {
        private const val TAG = "FeedTimelineViewModel"
    }

    init {
        // Idempotent (`if (started) return`). AppModule.onPostAuthenticated normally
        // starts the service, but that preload hook is DEFERRED on a session-restore
        // launch and never fires (the headless Authenticated branch waits for a "next
        // Authenticated" that doesn't come), leaving a returning user with an empty feed
        // until a full relogin. Starting here when the Feed screen opens makes the
        // timeline load regardless — a fresh login that already started the service just
        // no-ops this call.
        timelineService.start()
        // Idempotent — ensures the contact/connection streams are hydrating so author names
        // resolve even on a session-restore launch where chat hasn't been opened yet.
        contactService.start()
        viewModelScope.launch {
            val self = credentialsManager.getActiveCredentials()?.domain ?: return@launch
            val name = runCatching { publicProfileProvider.getPublicProfile(self)?.name }.getOrNull()
            if (!name.isNullOrBlank()) _selfName.value = self to name
        }
        viewModelScope.launch {
            timelineService.timeline.collect { posts ->
                _uiState.update {
                    it.copy(
                        posts = posts,
                        isLoading = false,
                        isRefreshing = false,
                    )
                }
            }
        }
    }

    /**
     * Pull older posts. v1 of the service keeps the whole local timeline in memory after its
     * cold-load, so this is a no-op there — kept so the list can call it on scroll without
     * branching and a future cursored variant slots in transparently.
     */
    fun loadMore() {
        if (_uiState.value.endReached) return
        viewModelScope.launch {
            try {
                timelineService.loadMore()
            } catch (t: Throwable) {
                Logger.e(throwable = t, tag = TAG) { "loadMore failed: ${t.message}" }
            }
        }
    }

    /**
     * Pull-to-refresh: re-run the service's cold-load via [FeedTimelineService.refresh],
     * which re-queries both source drives and re-emits the timeline. The spinner stays up
     * for the whole round-trip — [isRefreshing] is held true until `refresh()` returns and
     * cleared in `finally` so a failure (or the timeline re-emit clearing it early) can't
     * strand the indicator on or spin it forever.
     */
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

    fun onComposeClick() {
        _events.tryEmit(FeedTimelineEvent.NavigateToComposer)
    }

    /**
     * Fire-and-forget reaction toggle from a feed card. The optimistic write inside
     * [PostReactionService] updates the UI via the timeline re-emit; a failure rolls back
     * there and we surface a snackbar.
     */
    fun onToggleReaction(post: FeedPostItem, emoji: String) {
        viewModelScope.launch {
            try {
                reactionService.toggleReaction(post, emoji)
            } catch (t: Throwable) {
                Logger.e(throwable = t, tag = TAG) {
                    "toggleReaction failed for post=${post.id} emoji=$emoji: ${t.message}"
                }
                _events.tryEmit(FeedTimelineEvent.ShowSnackbar(MR.string.feed_reaction_failed))
            }
        }
    }
}

/** One-time effects the [FeedTimelineScreen] reacts to (navigation, snackbars). */
sealed interface FeedTimelineEvent {
    data class NavigateToDetail(val postId: Uuid) : FeedTimelineEvent
    data object NavigateToComposer : FeedTimelineEvent
    data class ShowSnackbar(val messageKey: StringResource) : FeedTimelineEvent
}
