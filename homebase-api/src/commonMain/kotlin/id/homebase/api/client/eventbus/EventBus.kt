package id.homebase.api.client.eventbus

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow

class EventBus(replay: Int = 1) {
    // Unbounded: a SharedFlow buffer only advances when every collector has taken a value, so any
    // finite capacity lets one stalled collector (e.g. on a wedged Main) park or drop for the whole
    // app. Most events can't be dropped, so a stalled collector costs memory instead.
    private val _events =
        MutableSharedFlow<BackendEvent>(replay = replay, extraBufferCapacity = Channel.UNLIMITED)
    val events: SharedFlow<BackendEvent> = _events.asSharedFlow()

    val subscriptionCount: StateFlow<Int> = _events.subscriptionCount

    suspend fun emit(event: BackendEvent) = _events.emit(event)

    fun tryEmit(event: BackendEvent): Boolean = _events.tryEmit(event)
}
