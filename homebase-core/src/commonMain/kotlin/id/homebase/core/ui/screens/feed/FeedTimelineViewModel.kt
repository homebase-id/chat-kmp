package id.homebase.core.ui.screens.feed

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import id.homebase.core.feed.services.FeedPostItem
import id.homebase.core.feed.services.FeedTimelineService
import id.homebase.core.feed.services.PostReactionService
import id.homebase.resources.MR
import id.homebase.resources.feed_reaction_failed
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import kotlin.uuid.Uuid

/**
 * Drives the native home timeline ([FeedTimelineScreen]).
 *
 * [FeedTimelineService] is an app-scoped singleton already started by
 * `AppModule.onPostAuthenticated`, so this VM only subscribes to its [timeline]
 * flow — it never calls `start()`. The first emission flips [FeedTimelineUiState.isLoading]
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
) : ViewModel() {

    private val _uiState = MutableStateFlow(FeedTimelineUiState(isLoading = true))
    val uiState: StateFlow<FeedTimelineUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<FeedTimelineEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<FeedTimelineEvent> = _events.asSharedFlow()

    companion object {
        private const val TAG = "FeedTimelineViewModel"
    }

    init {
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

    /** Pull-to-refresh: re-run the service's cold-load. */
    fun refresh() {
        _uiState.update { it.copy(isRefreshing = true) }
        viewModelScope.launch {
            try {
                // reset() + a fresh subscription cycle is heavy; the service re-cold-loads on
                // its own DriveEvent.Stopped path. Here we just nudge loadMore (no-op in v1)
                // and let the live timeline flow settle isRefreshing back to false on its next
                // emission. Guarded so a stuck refresh can't spin forever.
                timelineService.loadMore()
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
