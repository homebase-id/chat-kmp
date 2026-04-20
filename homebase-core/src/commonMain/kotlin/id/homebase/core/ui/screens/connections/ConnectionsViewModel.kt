package id.homebase.core.ui.screens.connections

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import id.homebase.api.client.auth.OwnerSessionRepository
import id.homebase.api.client.connections.ConnectionRequestProvider
import id.homebase.api.client.identity.PublicIdentityRepository
import id.homebase.api.common.OdinId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

class ConnectionsViewModel(
    private val connectionRequestProvider: ConnectionRequestProvider,
    private val publicIdentityRepository: PublicIdentityRepository,
    private val ownerSessionRepository: OwnerSessionRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ConnectionsUiState())
    val uiState: StateFlow<ConnectionsUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun onAction(action: ConnectionsUiAction) {
        when (action) {
            ConnectionsUiAction.Refresh -> refresh()

            ConnectionsUiAction.OpenOwnerConsoleClicked -> {
                val owner = ownerSessionRepository.user.value ?: return
                _uiState.update {
                    it.copy(uiEvent = ConnectionsUiEvent.OpenUrl(
                        "https://${owner.odinId.domainName}/owner/connections"
                    ))
                }
            }
        }
    }

    fun eventConsumed() {
        _uiState.update { it.copy(uiEvent = null) }
    }

    fun refresh() {
        _uiState.update { it.copy(isLoading = true) }
        viewModelScope.launch {
            try {
                val incoming = connectionRequestProvider.getIncomingRequests(1, 1000)
                val outgoing = connectionRequestProvider.getOutgoingRequests(1, 1000)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        incomingRequests = incoming.results,
                        outgoingRequests = outgoing.results,
                    )
                }
                val needed = buildSet {
                    incoming.results.forEach { add(it.senderOdinId) }
                    outgoing.results.forEach {
                        add(it.recipient)
                        it.introducerOdinId?.let { introducer -> add(introducer) }
                    }
                }
                loadIdentities(needed)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e(e) { "Failed to load connection requests" }
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        uiEvent = ConnectionsUiEvent.ActionError(e.message ?: "Failed to load requests"),
                    )
                }
            }
        }
    }

    private fun loadIdentities(odinIds: Set<OdinId>) {
        val missing = odinIds - _uiState.value.identities.keys
        missing.forEach { odinId ->
            viewModelScope.launch {
                try {
                    val identity = publicIdentityRepository.get(odinId)
                    _uiState.update { it.copy(identities = it.identities + (odinId to identity)) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Logger.w(e) { "Failed to load public identity for $odinId" }
                }
            }
        }
    }
}
