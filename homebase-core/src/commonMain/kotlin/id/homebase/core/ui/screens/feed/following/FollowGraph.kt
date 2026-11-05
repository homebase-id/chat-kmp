package id.homebase.core.ui.screens.feed.following

import co.touchlab.kermit.Logger
import id.homebase.api.client.drives.query.CursoredResult
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.client.follow.FollowNotificationType
import id.homebase.api.client.follow.FollowProvider
import id.homebase.api.client.follow.FollowRequest
import id.homebase.api.common.OdinId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Who you follow and who follows you, kept once for the app: the Following screen, the feed's following row,
 * Contacts' follow filters and the contact page all read and change the same lists.
 *
 * Domains stay raw lower-case-insensitive `String`s, so a row never pays the OdinId hash.
 */
data class FollowGraphState(
    val following: List<String> = emptyList(),
    val followers: List<String> = emptyList(),
    val isLoaded: Boolean = false,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
) {
    private val followingSet: Set<String> by lazy { following.mapTo(HashSet()) { it.lowercase() } }
    private val followerSet: Set<String> by lazy { followers.mapTo(HashSet()) { it.lowercase() } }

    fun isFollowing(domain: String): Boolean = domain.lowercase() in followingSet
    fun followsYou(domain: String): Boolean = domain.lowercase() in followerSet
}

class FollowGraph(
    private val followProvider: FollowProvider,
    private val eventBus: EventBus,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(FollowGraphState())
    val state: StateFlow<FollowGraphState> = _state.asStateFlow()

    private var loadJob: Job? = null

    companion object {
        private const val TAG = "FollowGraph"
        private const val PAGE_SIZE = 100

        // Bounds a runaway cursor; well past any real follow list.
        private const val MAX_PAGES = 20
    }

    init {
        scope.launch {
            eventBus.events.collect { event ->
                // Logout: drop the previous identity's lists
                if (event is BackendEvent.SessionEnded) reset()
            }
        }
    }

    /** Loads once; later calls are no-ops unless [force]. */
    fun ensureLoaded(force: Boolean = false) {
        val s = _state.value
        if (!force && (s.isLoaded || s.isLoading)) return
        loadJob?.cancel()
        loadJob = scope.launch { load() }
    }

    // Fanned out together, so a failure of either surfaces one error rather than half the lists
    private suspend fun load() {
        _state.update { it.copy(isLoading = true, errorMessage = null) }
        try {
            val (following, followers) = coroutineScope {
                val f = async { fetchAll { followProvider.fetchFollowing(cursor = it, count = PAGE_SIZE) } }
                val r = async { fetchAll { followProvider.fetchFollowers(cursor = it, count = PAGE_SIZE) } }
                f.await() to r.await()
            }
            _state.update {
                it.copy(following = following, followers = followers, isLoaded = true, isLoading = false)
            }
        } catch (t: Throwable) {
            Logger.e(throwable = t, tag = TAG) { "load failed: ${t.message}" }
            _state.update { it.copy(isLoading = false, errorMessage = t.message ?: "") }
        }
    }

    // Every page: the server pages by cursor and ends with a short or empty page
    private suspend fun fetchAll(page: suspend (cursor: String?) -> CursoredResult<List<String>>): List<String> {
        val all = mutableListOf<String>()
        var cursor: String? = null
        repeat(MAX_PAGES) {
            val result = page(cursor)
            all += result.results
            if (result.results.size < PAGE_SIZE || result.cursorState.isBlank()) return all
            cursor = result.cursorState
        }
        return all
    }

    /**
     * Optimistic, undone on failure (only this one: Follow all runs several at once), then rethrown for the
     * caller to report. A new follow pulls their existing posts into the feed.
     */
    suspend fun follow(odinId: String) {
        val wasFollowed = _state.value.isFollowing(odinId)
        if (!wasFollowed) _state.update { it.copy(following = it.following + odinId) }
        try {
            followProvider.follow(
                FollowRequest(
                    odinId = OdinId(odinId),
                    notificationType = FollowNotificationType.AllNotifications,
                    synchronizeFeedHistoryNow = true,
                )
            )
        } catch (t: Throwable) {
            if (!wasFollowed) removeFollowing(odinId)
            throw t
        }
    }

    suspend fun unfollow(odinId: String) {
        val previous = _state.value.following.firstOrNull { it.equals(odinId, ignoreCase = true) }
        removeFollowing(odinId)
        try {
            followProvider.unfollow(OdinId(odinId))
        } catch (t: Throwable) {
            if (previous != null) _state.update { it.copy(following = it.following + previous) }
            throw t
        }
    }

    private fun removeFollowing(odinId: String) = _state.update {
        it.copy(following = it.following.filterNot { id -> id.equals(odinId, ignoreCase = true) })
    }

    private fun reset() {
        loadJob?.cancel()
        _state.value = FollowGraphState()
    }
}
