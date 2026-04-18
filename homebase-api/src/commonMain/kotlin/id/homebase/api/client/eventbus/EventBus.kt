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

    /** Non-suspending emit. Returns false (and drops the event) when the buffer is full
     *  because a subscriber is slow. Use in paths that must not park on a slow collector —
     *  e.g. the outbox enqueue, which must return immediately so the chat Send button
     *  re-enables regardless of downstream subscriber state. */
    fun tryEmit(event: BackendEvent): Boolean = _events.tryEmit(event)
}
