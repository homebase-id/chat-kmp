package id.homebase.chat.services.calling

import id.homebase.core.notifications.CallPictureInPictureBridge
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

// Composition arms PiP but cannot disarm it: a backgrounded app composes nothing, so a remote hangup left the window up.
class CallPictureInPictureTeardown(
    private val states: Flow<CallState>,
    private val bridge: () -> CallPictureInPictureBridge?,
) {
    suspend fun run() {
        states.map { it.phase.holdsPictureInPicture }.distinctUntilChanged().filter { !it }.collect {
            bridge()?.stop()
        }
    }
}

val CallPhase.holdsPictureInPicture: Boolean get() = this == CallPhase.Connected || this == CallPhase.Reconnecting
