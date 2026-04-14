package id.homebase.chat.services.convo.contact

import id.homebase.api.client.connections.ConnectionNetworkProvider
import id.homebase.api.client.connections.RedactedIdentityConnectionRegistration
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.common.OdinId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import co.touchlab.kermit.Logger

data class ConnectionState(
    val isLoaded: Boolean,
    val map: Map<OdinId, RedactedIdentityConnectionRegistration>
)

class ConnectionService(
    private val provider: ConnectionNetworkProvider,
    private val eventBus: EventBus,
    private val scope: CoroutineScope
) {

    private val _connections =
        MutableStateFlow(ConnectionState(isLoaded = false, map = emptyMap()))

    val connections: StateFlow<ConnectionState> =
        _connections.asStateFlow()

    private var startJob: Job? = null

    init {
        // Keep the connected-identity map in sync with websocket events so downstream UI
        // (1:1 connection chips, conversation disclaimers) flips as soon as the server
        // reports an accepted/finalized connection — we don't wait for the next manual
        // refresh or app-foreground event.
        scope.launch {
            eventBus.events.collect { event ->
                if (event is BackendEvent.CircleNetworkEvent.ConnectionRequestAccepted ||
                    event is BackendEvent.CircleNetworkEvent.ConnectionRequestFinalized
                ) {
                    refresh()
                }
            }
        }
    }

    fun start() {
        if (startJob?.isActive == true) return
        startJob = scope.launch {
            refresh()
        }
    }

    suspend fun refresh() {
        try {
            coroutineScope {
                Logger.d { "Fetching connected and blocked connections in parallel..." }
                val connectedDeferred = async { provider.getConnected(1000, null) }
                val blockedDeferred = async { provider.getBlocked(1000, null) }
                val connected = connectedDeferred.await()
                val blocked = blockedDeferred.await()
                Logger.d { "Loaded connections ${connected.results.size} connected, ${blocked.results.size} blocked" }
                _connections.value = ConnectionState(
                    isLoaded = true,
                    map = (connected.results + blocked.results).associateBy { it.odinId }
                )
            }
        } catch (e: Exception) {
            Logger.e(e) {
                "ConnectionService.refresh failed: ${e.message}"
            }
            // still mark as loaded so UI doesn't stay in "loading" forever
            _connections.value = ConnectionState(
                isLoaded = true,
                map = emptyMap()
            )
        }
    }

    fun get(odinId: OdinId): RedactedIdentityConnectionRegistration? {
        return _connections.value.map[odinId]
    }
}