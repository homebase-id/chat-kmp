package id.homebase.chat.services.convo.contact

import id.homebase.api.client.connections.ConnectionNetworkProvider
import id.homebase.api.client.connections.RedactedIdentityConnectionRegistration
import kotlinx.coroutines.CoroutineScope
import id.homebase.api.common.OdinId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ConnectionService(
    private val provider: ConnectionNetworkProvider,
    private val scope: CoroutineScope
) {

    private val _connections =
        MutableStateFlow<Map<OdinId, RedactedIdentityConnectionRegistration>>(emptyMap())

    val connections: StateFlow<Map<OdinId, RedactedIdentityConnectionRegistration>> =
        _connections.asStateFlow()

    fun start() {
        scope.launch {
            runCatching { refresh() }
        }
    }

    suspend fun refresh() {
        try {
            val connected = provider.getConnected(1000, null)
            val blocked = provider.getBlocked(1000, null)

            val merged =
                (connected.results + blocked.results)
                    .associateBy { it.odinId }

            _connections.value = merged

        } catch (e: Exception) {
            // don’t crash UI layer
            // optionally log
        }
    }

    fun get(odinId: OdinId): RedactedIdentityConnectionRegistration? {
        return _connections.value[odinId]
    }
}