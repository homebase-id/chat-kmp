package id.homebase.core.util

import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

class JvmNetworkMonitor : NetworkMonitor {

    // Desktop is assumed to be on a wired/Wi-Fi link; there is no metered signal to read.
    override fun isUnmetered(): Boolean = true

    // The JDK has no network-change callback, so the default route is polled.
    override val defaultNetworkChanges: Flow<Unit>
        get() = flow {
            while (true) {
                emit(defaultRoute())
                delay(POLL_MS)
            }
        }.flowOn(Dispatchers.IO).defaultNetworkSwitches()

    // Connecting a UDP socket sends nothing; it only asks the OS which interface and address route to the internet.
    private fun defaultRoute(): String? = try {
        DatagramSocket().use { socket ->
            socket.connect(ROUTE_PROBE, 53)
            val local = socket.localAddress
            if (local.isAnyLocalAddress) null else "${NetworkInterface.getByInetAddress(local)?.name}/${local.hostAddress}"
        }
    } catch (_: SocketException) {
        null // no route: the OS has no default network
    }
}

private const val POLL_MS = 2_000L
private val ROUTE_PROBE: InetAddress = InetAddress.getByAddress(byteArrayOf(8, 8, 8, 8))
