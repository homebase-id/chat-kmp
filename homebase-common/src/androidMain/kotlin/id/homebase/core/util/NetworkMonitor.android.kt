package id.homebase.core.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.core.content.getSystemService
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emptyFlow

class AndroidNetworkMonitor(private val context: Context) : NetworkMonitor {

    override fun isUnmetered(): Boolean {
        val manager = context.getSystemService<ConnectivityManager>() ?: return false
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    override val defaultNetworkChanges: Flow<Unit>
        get() {
            val manager = context.getSystemService<ConnectivityManager>() ?: return emptyFlow()
            return callbackFlow<Network?> {
                // Registering replays the current default network as onAvailable; it equals this seed, so it doesn't emit.
                trySend(manager.activeNetwork)
                val callback = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) { trySend(network) }
                    override fun onLost(network: Network) { trySend(null) }
                }
                manager.registerDefaultNetworkCallback(callback)
                awaitClose { manager.unregisterNetworkCallback(callback) }
            }.defaultNetworkSwitches()
        }
}
