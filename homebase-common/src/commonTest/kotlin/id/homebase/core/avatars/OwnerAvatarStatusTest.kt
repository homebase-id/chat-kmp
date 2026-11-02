package id.homebase.core.avatars

import id.homebase.api.client.eventbus.BackendEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class OwnerAvatarStatusTest {
    @Test
    fun connectionChangeAndSyncResultReachTheState() = runTest(UnconfinedTestDispatcher()) {
        val connection = MutableStateFlow(AppConnectionStatus.Connecting)
        val events = MutableSharedFlow<BackendEvent>(extraBufferCapacity = 8)
        val status = OwnerAvatarStatus(connection, events, backgroundScope)
        backgroundScope.launch { status.state.collect {} }

        assertEquals(OwnerAvatarState(AppConnectionStatus.Connecting, driveIsSyncing = false, hasDriveError = false), status.state.value)

        connection.value = AppConnectionStatus.Connected
        assertEquals(AppConnectionStatus.Connected, status.state.value.connectionStatus)

        events.emit(BackendEvent.SyncAllStarted)
        assertEquals(OwnerAvatarState(AppConnectionStatus.Connected, driveIsSyncing = true, hasDriveError = false), status.state.value)

        events.emit(BackendEvent.SyncAllStopped(BackendEvent.SyncAllResult.Failure))
        assertEquals(OwnerAvatarState(AppConnectionStatus.Connected, driveIsSyncing = false, hasDriveError = true), status.state.value)

        events.emit(BackendEvent.SyncAllStarted)
        events.emit(BackendEvent.SyncAllStopped(BackendEvent.SyncAllResult.Success))
        assertEquals(OwnerAvatarState(AppConnectionStatus.Connected, driveIsSyncing = false, hasDriveError = false), status.state.value)
    }
}
