package id.homebase.core.util

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Metered-vs-unmetered for the active network, and when the default network changes. The WebSocket-derived
 * `AuthConnectionCoordinator.isOnline` only answers "connected at all".
 */
interface NetworkMonitor {
    /** True only when the active network is known to be unmetered. Unknown counts as metered. */
    fun isUnmetered(): Boolean

    /**
     * Emits once each time the default network becomes a different network (Wi-Fi to cellular, cellular to Wi-Fi,
     * any network after none; one Wi-Fi to another where the platform can tell them apart). Never for the network in use when collection starts, for losing the
     * network with no replacement, or for signal, bandwidth or metering changes on the same network. Cold: each
     * collector runs its own watch. Web never emits: browsers have no portable default-network signal.
     */
    val defaultNetworkChanges: Flow<Unit>
}

/** [NetworkMonitor.defaultNetworkChanges] over the default network's identity (null for none), first value current. */
internal fun <K : Any> Flow<K?>.defaultNetworkSwitches(): Flow<Unit> = flow {
    var started = false
    var last: K? = null
    collect { network ->
        if (started && network != null && network != last) emit(Unit)
        started = true
        last = network
    }
}
