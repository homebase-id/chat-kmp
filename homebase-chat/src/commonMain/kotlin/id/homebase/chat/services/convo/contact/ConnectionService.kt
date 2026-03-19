package id.homebase.chat.services.convo.contact

import id.homebase.api.client.connections.ConnectionNetworkProvider
import id.homebase.api.client.connections.RedactedIdentityConnectionRegistration
import id.homebase.api.common.OdinId
import kotlinx.coroutines.CoroutineScope
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

    init {
        scope.launch {
            refresh()
        }
    }

    fun start() {
        scope.launch {
            refresh()
        }
    }

    suspend fun refresh() {
        try {
            Logger.d { "Fetching connected connections..." }
            val connected = provider.getConnected(1000, null)

            Logger.d { "Loaded connections ${connected.results.size}..." }

            Logger.d { "Fetching blocked connections..." }
            val blocked = provider.getBlocked(1000, null)

            val merged =
                (connected.results + blocked.results)
                    .associateBy { it.odinId }

            _connections.value = ConnectionState(
                isLoaded = true,
                map = merged
            )

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