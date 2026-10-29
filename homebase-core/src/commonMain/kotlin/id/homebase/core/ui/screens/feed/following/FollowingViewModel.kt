package id.homebase.core.ui.screens.feed.following

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import id.homebase.api.client.connections.ConnectionStatus
import id.homebase.api.client.follow.FollowProvider
import id.homebase.api.common.OdinId
import id.homebase.chat.services.convo.contact.ConnectionService
import id.homebase.chat.services.convo.contact.ContactService
import id.homebase.core.settings.DeveloperPreferences
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
    private val followGraph: FollowGraph,
    private val followProvider: FollowProvider,
    private val contactService: ContactService,
    private val connectionService: ConnectionService,
    private val developerPreferences: DeveloperPreferences,
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
    }

    // Opening on Suggested when you follow no one is decided once, on the first load
    private var openedOnSuggestions = false

    init {
        // Idempotent — hydrate contact/connection streams so follow-list names resolve.
        contactService.start()
        // Fresh each time the screen opens; the rest of the app reads the same lists
        followGraph.ensureLoaded(force = true)
        viewModelScope.launch {
            followGraph.state.collect { graph ->
                _uiState.update {
                    val openOnSuggested = !openedOnSuggestions && graph.isLoaded && graph.following.isEmpty() &&
                        it.selectedTab == FollowTab.Following
                    if (graph.isLoaded) openedOnSuggestions = true
                    it.copy(
                        following = graph.following,
                        followers = graph.followers,
                        isLoading = graph.isLoading,
                        errorMessage = graph.errorMessage,
                        selectedTab = if (openOnSuggested) FollowTab.Suggested else it.selectedTab,
                    )
                }
            }
        }
        // Suggestions come from the connection list, which updates as it loads and as people connect
        viewModelScope.launch {
            connectionService.connections.collect { state ->
                val connected = state.map
                    .filterValues { it.status == ConnectionStatus.Connected }
                    .keys.map { it.domainName }
                _uiState.update { it.copy(connections = connected) }
            }
        }
        viewModelScope.launch {
            developerPreferences.advancedFeedEnabled.collect { enabled ->
                _uiState.update { it.copy(advancedFeed = enabled) }
                if (enabled && _uiState.value.peopleYouMayKnowBasis == null) loadPeopleYouMayKnow(refresh = false)
            }
        }
    }

    fun selectTab(tab: FollowTab) {
        _uiState.update { it.copy(selectedTab = tab) }
    }

    fun onIdentityClick(odinId: String) {
        _events.tryEmit(FollowingEvent.NavigateToIdentity(odinId))
    }

    fun load() = followGraph.ensureLoaded(force = true)

    // The server asks every connection, so it is slow the first time and cached for an hour after
    fun loadPeopleYouMayKnow(refresh: Boolean) {
        if (_uiState.value.peopleYouMayKnowLoading) return
        _uiState.update { it.copy(peopleYouMayKnowLoading = true) }
        viewModelScope.launch {
            try {
                val result = followProvider.fetchSuggestions(refresh = refresh)
                _uiState.update {
                    it.copy(
                        peopleYouMayKnow = result.results,
                        peopleYouMayKnowBasis = result.answered to result.asked,
                        peopleYouMayKnowLoading = false,
                    )
                }
            } catch (t: Throwable) {
                // Quietly absent: the section is extra, and an older server has no suggestions route
                Logger.w(throwable = t, tag = TAG) { "suggestions failed: ${t.message}" }
                _uiState.update { it.copy(peopleYouMayKnowLoading = false) }
            }
        }
    }

    fun follow(odinId: String) {
        viewModelScope.launch {
            try {
                followGraph.follow(odinId)
            } catch (t: Throwable) {
                Logger.e(throwable = t, tag = TAG) { "follow $odinId failed: ${t.message}" }
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
        viewModelScope.launch {
            try {
                followGraph.unfollow(odinId)
            } catch (t: Throwable) {
                Logger.e(throwable = t, tag = TAG) { "unfollow $odinId failed: ${t.message}" }
                _events.tryEmit(FollowingEvent.ShowSnackbar(t.message ?: odinId))
            }
        }
    }
}
