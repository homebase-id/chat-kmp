package id.homebase.core.ui.screens.moments

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.homebase.api.client.auth.OwnerSessionRepository
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import id.homebase.core.auth.AuthConnectionCoordinator
import id.homebase.core.auth.toConnectionStatus
import id.homebase.core.moments.services.MomentsFeedService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Thin wrapper over [MomentsFeedService] for the feed screen. The service is a
 * singleton started by `AppModule.onPostAuthenticated`, so the VM only
 * subscribes — no start() call here.
 *
 * Also surfaces the owner session and connection/sync status the screen needs
 * to render the chat-style header (avatar with status indicators).
 */
class MomentsFeedViewModel(
    feedService: MomentsFeedService,
    ownerSessionRepository: OwnerSessionRepository,
    authConnectionCoordinator: AuthConnectionCoordinator,
    eventBus: EventBus,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MomentsFeedUiState())
    val uiState: StateFlow<MomentsFeedUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            feedService.feed.collect { list ->
                _uiState.update { it.copy(moments = list) }
            }
        }

        viewModelScope.launch {
            ownerSessionRepository.user.collect { session ->
                _uiState.update { it.copy(ownerSession = session) }
            }
        }

        viewModelScope.launch {
            authConnectionCoordinator.connectionState.collectLatest { state ->
                _uiState.update { it.copy(connectionStatus = state.toConnectionStatus()) }
            }
        }

        viewModelScope.launch {
            eventBus.events
                .filter {
                    it is BackendEvent.SyncAllStarted ||
                        it is BackendEvent.SyncAllStopped
                }
                .collectLatest { event ->
                    when (event) {
                        is BackendEvent.SyncAllStarted -> _uiState.update {
                            it.copy(driveIsSyncing = true, hasDriveError = false)
                        }

                        is BackendEvent.SyncAllStopped -> _uiState.update {
                            it.copy(
                                driveIsSyncing = false,
                                hasDriveError = event.result is BackendEvent.SyncAllResult.Failure,
                            )
                        }

                        else -> Unit
                    }
                }
        }
    }
}
