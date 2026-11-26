package id.homebase.core.util

import java.net.DatagramSocket
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
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
                emit(ROUTE_PROBES.firstNotNullOfOrNull(::routeTo))
                delay(POLL_MS)
            }
        }.flowOn(Dispatchers.IO).defaultNetworkSwitches()

    // Connecting a UDP socket sends nothing; it only asks the OS which interface and address route to the internet.
    // The SocketAddress overload throws SocketException for no route; the InetAddress one throws UncheckedIOException.
    private fun routeTo(probe: InetAddress): String? = try {
        DatagramSocket().use { socket ->
            socket.connect(InetSocketAddress(probe, 53))
            val local = socket.localAddress
            // An IPv6 source address rotates (privacy addresses) on the same network, so only its /64 prefix counts.
            val address = if (local is Inet6Address) local.address.copyOf(8).joinToString(":") else local.hostAddress
            if (local.isAnyLocalAddress) null else "${NetworkInterface.getByInetAddress(local)?.name}/$address"
        }
    } catch (_: SocketException) {
        null
    }
}

private const val POLL_MS = 2_000L
// IPv4 first, then IPv6 for an IPv6-only network.
private val ROUTE_PROBES = listOf("8.8.8.8", "2001:4860:4860::8888").map(InetAddress::getByName)
