package id.homebase.core.ui.screens.feed.following

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import id.homebase.api.client.follow.FollowNotificationType
import id.homebase.api.client.follow.FollowProvider
import id.homebase.api.client.follow.FollowRequest
import id.homebase.api.common.OdinId
import id.homebase.chat.services.convo.contact.ContactService
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * One-time effects the [FollowingScreen] reacts to. Navigation and snackbar are
 * effects, not state — they fire once and must not replay on recomposition or
 * config change, so they ride a [SharedFlow] rather than living in
 * [FollowingUiState].
 */
sealed interface FollowingEvent {
    data class ShowSnackbar(val message: String) : FollowingEvent
    data class NavigateToIdentity(val odinId: String) : FollowingEvent
}

/**
 * Backs the Following/Followers screen. Loads both lists in parallel and lets
 * the user follow/unfollow an identity with an optimistic list update that
 * rolls back if the network call fails.
 */
class FollowingViewModel(
    private val followProvider: FollowProvider,
    private val contactService: ContactService,
) : ViewModel() {

    private val _uiState = MutableStateFlow(FollowingUiState())
    val uiState: StateFlow<FollowingUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<FollowingEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<FollowingEvent> = _events.asSharedFlow()

    /**
     * Reactive `odinId → resolved display name`, sourced from [ContactService]. Identities in
     * the follow lists that we have a saved contact/connection for show a real name; the rest
     * fall back to the raw domain in the screen (web `AuthorName` parity).
     */
    val displayNames: StateFlow<Map<OdinId, String>> =
        contactService.contacts
            .map { contacts -> contacts.associate { it.odinId to it.name } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    companion object {
        private const val TAG = "FollowingViewModel"
        private const val PAGE_SIZE = 100
    }

    init {
        // Idempotent — hydrate contact/connection streams so follow-list names resolve.
        contactService.start()
        load()
    }

    fun selectTab(tab: FollowTab) {
        _uiState.update { it.copy(selectedTab = tab) }
    }

    fun onIdentityClick(odinId: String) {
        _events.tryEmit(FollowingEvent.NavigateToIdentity(odinId))
    }

    /**
     * Fetch the following + followers lists concurrently. The two calls are
     * independent reads, so we fan them out with [async] inside a
     * [coroutineScope] and await both — a failure of either cancels the other
     * and surfaces a single error rather than a half-populated screen.
     */
    fun load() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                val (following, followers) = coroutineScope {
                    val followingDeferred = async {
                        followProvider.fetchFollowing(max = PAGE_SIZE).results
                    }
                    val followersDeferred = async {
                        followProvider.fetchFollowers(max = PAGE_SIZE).results
                    }
                    followingDeferred.await() to followersDeferred.await()
                }
                _uiState.update {
                    it.copy(
                        following = following,
                        followers = followers,
                        isLoading = false,
                        errorMessage = null,
                    )
                }
            } catch (t: Throwable) {
                Logger.e(throwable = t, tag = TAG) { "load failed: ${t.message}" }
                _uiState.update { it.copy(isLoading = false, errorMessage = t.message) }
            }
        }
    }

    /**
     * Optimistically add [odinId] to the following list, then call the server.
     * On failure roll the list back and surface a snackbar. A re-follow is
     * idempotent server-side, so the optimistic add is safe even if the row was
     * somehow already present (the de-dup guard keeps the list clean).
     */
    fun follow(odinId: String) {
        val previous = _uiState.value.following
        if (previous.none { it.equals(odinId, ignoreCase = true) }) {
            _uiState.update { it.copy(following = it.following + odinId) }
        }
        viewModelScope.launch {
            try {
                followProvider.follow(
                    FollowRequest(
                        odinId = OdinId(odinId),
                        notificationType = FollowNotificationType.AllNotifications,
                    ),
                )
            } catch (t: Throwable) {
                Logger.e(throwable = t, tag = TAG) { "follow $odinId failed: ${t.message}" }
                _uiState.update { it.copy(following = previous) }
                _events.tryEmit(FollowingEvent.ShowSnackbar(t.message ?: odinId))
            }
        }
    }

    /**
     * Optimistically drop [odinId] from the following list, then call the
     * server. Restore the prior list and surface a snackbar on failure.
     */
    fun unfollow(odinId: String) {
        val previous = _uiState.value.following
        _uiState.update {
            it.copy(following = it.following.filterNot { id -> id.equals(odinId, ignoreCase = true) })
        }
        viewModelScope.launch {
            try {
                followProvider.unfollow(OdinId(odinId))
            } catch (t: Throwable) {
                Logger.e(throwable = t, tag = TAG) { "unfollow $odinId failed: ${t.message}" }
                _uiState.update { it.copy(following = previous) }
                _events.tryEmit(FollowingEvent.ShowSnackbar(t.message ?: odinId))
            }
        }
    }
}
