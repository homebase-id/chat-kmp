package id.homebase.core.util

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

object WebNetworkMonitor : NetworkMonitor {
    override fun isUnmetered(): Boolean = true
    override val defaultNetworkChanges: Flow<Unit> = emptyFlow()
}
