package id.homebase.core.avatars

import id.homebase.api.client.eventbus.BackendEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn

data class OwnerAvatarState(
    val connectionStatus: AppConnectionStatus = AppConnectionStatus.Connecting,
    val driveIsSyncing: Boolean = false,
    val hasDriveError: Boolean = false,
)

/** The one source of the owner avatar's connection/sync ring, read by the chat list and the rail. */
class OwnerAvatarStatus(
    connection: Flow<AppConnectionStatus>,
    events: Flow<BackendEvent>,
    scope: CoroutineScope,
) {
    private val sync: Flow<Pair<Boolean, Boolean>> = events
        .filter { it is BackendEvent.SyncAllStarted || it is BackendEvent.SyncAllStopped }
        .map { event ->
            when (event) {
                is BackendEvent.SyncAllStopped -> false to (event.result is BackendEvent.SyncAllResult.Failure)
                else -> true to false
            }
        }
        .onStart { emit(false to false) }

    val state: StateFlow<OwnerAvatarState> = combine(connection, sync) { status, (syncing, error) ->
        OwnerAvatarState(status, syncing, error)
    }.stateIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), OwnerAvatarState())

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
