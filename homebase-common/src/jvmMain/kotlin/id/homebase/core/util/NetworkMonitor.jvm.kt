package id.homebase.core.util

import java.net.NetworkInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

class JvmNetworkMonitor : NetworkMonitor {

    // Desktop is assumed to be on a wired/Wi-Fi link; there is no metered signal to read.
    override fun isUnmetered(): Boolean = true

    // The JDK has no default-network API or change callback, so up interfaces are polled; a VPN coming up also counts.
    override val defaultNetworkChanges: Flow<Unit>
        get() = flow {
            while (true) {
                emit(upInterfaces())
                delay(POLL_MS)
            }
        }.flowOn(Dispatchers.IO).defaultNetworkSwitches()

    private fun upInterfaces(): Set<String>? = NetworkInterface.networkInterfaces()
        .filter { it.isUp && !it.isLoopback && !it.isVirtual }
        .flatMap { iface -> iface.inetAddresses().filter { !it.isLinkLocalAddress }.map { "${iface.name}/${it.hostAddress}" } }
        .toList()
        .toSet()
        .ifEmpty { null }
}

private const val POLL_MS = 2_000L
