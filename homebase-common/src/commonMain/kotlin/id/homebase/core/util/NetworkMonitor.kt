package id.homebase.core.util

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Metered-vs-unmetered for the active network. The WebSocket-derived
 * `AuthConnectionCoordinator.isOnline` only answers "connected at all".
 */
interface NetworkMonitor {
    /** True only when the active network is known to be unmetered. Unknown counts as metered. */
    fun isUnmetered(): Boolean

    /**
     * Emits once each time the device's default network becomes a different network: Wi-Fi to cellular, cellular to
     * Wi-Fi, another Wi-Fi network, or any network after none. It does not emit for the network already in use when
     * collection starts, when the network is lost with nothing to replace it, or for signal strength, bandwidth or
     * metering changes on the same network. Cold: each collector starts its own watch and stops it on cancellation.
     *
     * Android and iOS follow the OS default network. Desktop has no default-network API, so it watches the set of
     * up interfaces and their addresses, which also emits when a VPN or a second interface comes or goes. Web never
     * emits: browsers expose no portable default-network signal, and the web build has no calls to move.
     */
    val defaultNetworkChanges: Flow<Unit>
}

/**
 * The [NetworkMonitor.defaultNetworkChanges] rule over a flow of the current default network's identity, null when
 * there is none, whose first value is the network at collection time.
 */
fun <K : Any> Flow<K?>.defaultNetworkSwitches(): Flow<Unit> = flow {
    var started = false
    var last: K? = null
    collect { network ->
        if (started && network != null && network != last) emit(Unit)
        started = true
        last = network
    }
}
