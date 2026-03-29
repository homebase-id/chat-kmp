package id.homebase.chat.services.convo.contact

import id.homebase.api.client.connections.ConnectionNetworkProvider
import id.homebase.api.client.connections.RedactedIdentityConnectionRegistration
import id.homebase.api.common.OdinId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import co.touchlab.kermit.Logger
import org.koin.core.component.getScopeId

data class ConnectionState(
    val isLoaded: Boolean,
    val map: Map<OdinId, RedactedIdentityConnectionRegistration>
)

class ConnectionService(
    private val provider: ConnectionNetworkProvider,
    private val scope: CoroutineScope
) {

    private val _connections =
        MutableStateFlow(ConnectionState(isLoaded = false, map = emptyMap()))

    val connections: StateFlow<ConnectionState> =
        _connections.asStateFlow()

    fun start() {
        scope.launch {
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