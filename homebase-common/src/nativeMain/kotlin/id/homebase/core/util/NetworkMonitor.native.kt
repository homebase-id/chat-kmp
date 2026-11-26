package id.homebase.core.util

import kotlinx.atomicfu.atomic
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import platform.Network.nw_endpoint_copy_address_string
import platform.Network.nw_interface_get_name
import platform.Network.nw_path_enumerate_gateways
import platform.Network.nw_path_enumerate_interfaces
import platform.Network.nw_path_get_status
import platform.Network.nw_path_is_constrained
import platform.Network.nw_path_is_expensive
import platform.Network.nw_path_monitor_cancel
import platform.Network.nw_path_monitor_create
import platform.Network.nw_path_monitor_set_queue
import platform.Network.nw_path_monitor_set_update_handler
import platform.Network.nw_path_monitor_start
import platform.Network.nw_path_status_satisfied
import platform.Network.nw_path_t
import platform.darwin.DISPATCH_QUEUE_PRIORITY_DEFAULT
import platform.darwin.dispatch_get_global_queue
import platform.darwin.dispatch_queue_create
import platform.posix.free

/** NWPathMonitor pushes; [isUnmetered] reads the latest push, so it never blocks the caller. */
@OptIn(ExperimentalForeignApi::class)
class IosNetworkMonitor : NetworkMonitor {

    private val unmetered = atomic(false)

    init {
        val monitor = nw_path_monitor_create()
        nw_path_monitor_set_queue(
            monitor,
            dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0u),
        )
        nw_path_monitor_set_update_handler(monitor) { path ->
            unmetered.value = path != null &&
                nw_path_get_status(path) == nw_path_status_satisfied &&
                !nw_path_is_expensive(path) &&
                !nw_path_is_constrained(path)
        }
        nw_path_monitor_start(monitor)
    }

    override fun isUnmetered(): Boolean = unmetered.value

    // A new monitor delivers the current path first, which seeds the rule.
    override val defaultNetworkChanges: Flow<Unit>
        get() = callbackFlow {
            val monitor = nw_path_monitor_create()
            // Serial, so two quick path updates cannot reach the switch rule out of order.
            nw_path_monitor_set_queue(monitor, dispatch_queue_create("id.homebase.network-changes", null))
            nw_path_monitor_set_update_handler(monitor) { path -> trySend(primaryInterface(path)) }
            nw_path_monitor_start(monitor)
            awaitClose { nw_path_monitor_cancel(monitor) }
        }.defaultNetworkSwitches()

    // Interfaces come in preference order, so the first carries the traffic; the gateways tell two Wi-Fi networks apart.
    private fun primaryInterface(path: nw_path_t): String? {
        if (path == null || nw_path_get_status(path) != nw_path_status_satisfied) return null
        var name: String? = null
        nw_path_enumerate_interfaces(path) { iface ->
            name = nw_interface_get_name(iface)?.toKString()
            false
        }
        val gateways = mutableListOf<String>()
        nw_path_enumerate_gateways(path) { gateway ->
            nw_endpoint_copy_address_string(gateway)?.let { gateways += it.toKString(); free(it) }
            true
        }
        return "$name|${gateways.sorted().joinToString(",")}"
    }
}
