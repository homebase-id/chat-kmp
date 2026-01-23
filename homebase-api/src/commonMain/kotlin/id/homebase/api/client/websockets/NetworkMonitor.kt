package id.homebase.homebasekmppoc.prototype.lib.websockets

import kotlinx.coroutines.flow.StateFlow

interface NetworkMonitor {
    val isOnline: StateFlow<Boolean>
}
