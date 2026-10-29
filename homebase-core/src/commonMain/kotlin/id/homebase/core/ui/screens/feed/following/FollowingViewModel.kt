package id.homebase.core.ui.screens.feed.following

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import id.homebase.api.client.connections.ConnectionStatus
import id.homebase.api.client.drives.query.CursoredResult
import id.homebase.api.client.follow.FollowNotificationType
import id.homebase.api.client.follow.FollowProvider
import id.homebase.api.client.follow.FollowRequest
import id.homebase.api.common.OdinId
import id.homebase.chat.services.convo.contact.ConnectionService
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

sealed interface FollowingEvent {
    data class ShowSnackbar(val message: String) : FollowingEvent
    data object InvalidAddress : FollowingEvent
    data class NavigateToIdentity(val odinId: String) : FollowingEvent
}

class FollowingViewModel(
    private val followProvider: FollowProvider,
    private val contactService: ContactService,
    private val connectionService: ConnectionService,
) : ViewModel() {

    private val _uiState = MutableStateFlow(FollowingUiState())
    val uiState: StateFlow<FollowingUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<FollowingEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<FollowingEvent> = _events.asSharedFlow()

    val displayNames: StateFlow<Map<OdinId, String>> =
        contactService.contacts
            .map { contacts -> contacts.associate { it.odinId to it.name } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    companion object {
        private const val TAG = "FollowingViewModel"
        private const val PAGE_SIZE = 100

        // Bounds a runaway cursor; well past any real follow list.
        private const val MAX_PAGES = 20
    }

    init {
        // Idempotent — hydrate contact/connection streams so follow-list names resolve.
        contactService.start()
        load()
        // Suggestions come from the connection list, which updates as it loads and as people connect
        viewModelScope.launch {
            connectionService.connections.collect { state ->
                val connected = state.map
                    .filterValues { it.status == ConnectionStatus.Connected }
                    .keys.map { it.domainName }
                _uiState.update { it.copy(connections = connected) }
            }
        }
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
                        fetchAll { cursor -> followProvider.fetchFollowing(cursor = cursor, count = PAGE_SIZE) }
                    }
                    val followersDeferred = async {
                        fetchAll { cursor -> followProvider.fetchFollowers(cursor = cursor, count = PAGE_SIZE) }
                    }
                    followingDeferred.await() to followersDeferred.await()
                }
                _uiState.update {
                    it.copy(
                        following = following,
                        followers = followers,
                        isLoading = false,
                        errorMessage = null,
                        // Someone who follows no one yet starts on the people they could follow
                        selectedTab = if (following.isEmpty() && it.selectedTab == FollowTab.Following)
                            FollowTab.Suggested else it.selectedTab,
                    )
                }
            } catch (t: Throwable) {
                Logger.e(throwable = t, tag = TAG) { "load failed: ${t.message}" }
                _uiState.update { it.copy(isLoading = false, errorMessage = t.message) }
            }
        }
    }

    // Every page, so the lists are complete: the server pages by cursor and ends with a short or empty page.
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

    // A re-follow is idempotent server-side, so the optimistic add is safe even if the row was already present.
    fun follow(odinId: String) {
        val wasFollowed = _uiState.value.following.any { it.equals(odinId, ignoreCase = true) }
        if (!wasFollowed) {
            _uiState.update { it.copy(following = it.following + odinId) }
        }
        viewModelScope.launch {
            try {
                followProvider.follow(
                    FollowRequest(
                        odinId = OdinId(odinId),
                        notificationType = FollowNotificationType.AllNotifications,
                        // Their existing posts show in the feed straight away, not only new ones
                        synchronizeFeedHistoryNow = true,
                    ),
                )
            } catch (t: Throwable) {
                Logger.e(throwable = t, tag = TAG) { "follow $odinId failed: ${t.message}" }
                // Undo only this one: "Follow all" runs several at once
                if (!wasFollowed) {
                    _uiState.update {
                        it.copy(following = it.following.filterNot { id -> id.equals(odinId, ignoreCase = true) })
                    }
                }
                _events.tryEmit(FollowingEvent.ShowSnackbar(t.message ?: odinId))
            }
        }
    }

    fun followAll(odinIds: List<String>) {
        odinIds.forEach(::follow)
    }

    fun openFollowByAddress() = _uiState.update { it.copy(isFollowByAddressOpen = true) }

    fun closeFollowByAddress() = _uiState.update { it.copy(isFollowByAddressOpen = false) }

    // A Homebase address such as frodo.dotyou.cloud; a pasted https:// or trailing path is trimmed off.
    fun followByAddress(input: String) {
        val domain = input.trim().removePrefix("https://").removePrefix("http://").substringBefore('/').lowercase()
        if (!OdinId.isValid(domain)) {
            _events.tryEmit(FollowingEvent.InvalidAddress)
            return
        }
        closeFollowByAddress()
        _uiState.update { it.copy(selectedTab = FollowTab.Following) }
        follow(domain)
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
