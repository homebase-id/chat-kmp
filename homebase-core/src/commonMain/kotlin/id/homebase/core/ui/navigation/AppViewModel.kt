package id.homebase.core.ui.navigation

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.common.OdinId
import id.homebase.chat.data.IncomingConnectionRequestUiModel
import id.homebase.chat.services.requests.ConnectionRequestService
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

class AppViewModel(
    private val connectionRequestService: ConnectionRequestService,
    private val credentialsManager: CredentialsManager,
) : ViewModel() {
    private val _uiState = MutableStateFlow(AppUiState())
    val uiState: StateFlow<AppUiState> = _uiState.asStateFlow()

    private var credentialsJob: Job? = null
    private var listenForConnectionRequestsJob: Job? = null

    fun refreshData() {
        credentialsJob?.cancel()
        credentialsJob = viewModelScope.launch {
            credentialsManager.credentialsFlow.collect { credentials ->
                if (credentials != null) {
                    _uiState.update { it.copy(currentOdinId = credentials.domain) }
                    listenForConnectionRequests()
                } else {
                    listenForConnectionRequestsJob?.cancel()
                }
            }
        }
    }

    private fun listenForConnectionRequests() {
        listenForConnectionRequestsJob?.cancel()
        listenForConnectionRequestsJob = viewModelScope.launch {
            try {
                connectionRequestService.start()
                connectionRequestService.incomingRequests.collect { incomingRequests ->
                    _uiState.update { it.copy(incomingRequests = incomingRequests) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e("AppViewModel", e) { "Failed to collect incoming requests: ${e.message}" }
            }
        }
    }
}

@Immutable
data class AppUiState(
    val currentOdinId: OdinId? = null,
    val incomingRequests: List<IncomingConnectionRequestUiModel> = listOf(),
)
