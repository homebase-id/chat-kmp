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

// Effects, not state — they fire once and must not replay on recomposition, so they ride a SharedFlow.
sealed interface FollowingEvent {
    data class ShowSnackbar(val message: String) : FollowingEvent
    data class NavigateToIdentity(val odinId: String) : FollowingEvent
}

class FollowingViewModel(
    private val followProvider: FollowProvider,
    private val contactService: ContactService,
) : ViewModel() {

    private val _uiState = MutableStateFlow(FollowingUiState())
    val uiState: StateFlow<FollowingUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<FollowingEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<FollowingEvent> = _events.asSharedFlow()

    /** Identities with no saved contact/connection fall back to the raw domain in the screen. */
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

    // Fanned out with async so a failure of either cancels the other and surfaces one error rather than a
    // half-populated screen.
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

    // A re-follow is idempotent server-side, so the optimistic add is safe even if the row was already present.
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
