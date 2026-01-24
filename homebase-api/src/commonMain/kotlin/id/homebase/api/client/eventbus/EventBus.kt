package id.homebase.api.client.eventbus

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class EventBus(
    replay: Int = 1,
    extraBufferCapacity: Int = 10
) {
    private val _events =
        MutableSharedFlow<BackendEvent>(replay = replay, extraBufferCapacity = extraBufferCapacity)
    val events: SharedFlow<BackendEvent> = _events.asSharedFlow()

    suspend fun emit(event: BackendEvent) = _events.emit(event)
}
