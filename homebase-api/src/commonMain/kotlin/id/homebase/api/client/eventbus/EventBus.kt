package id.homebase.api.client.eventbus

import kotlinx.coroutines.channels.BufferOverflow
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

    // High-volume progress ticks. Each is superseded by the next and the item's end state arrives
    // losslessly on [events] (ItemCompleted/ItemFailed/…), so a lagging collector may skip ticks.
    private val _progress = MutableSharedFlow<BackendEvent>(
        extraBufferCapacity = PROGRESS_BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val progress: SharedFlow<BackendEvent> = _progress.asSharedFlow()

    val subscriptionCount: StateFlow<Int> = _events.subscriptionCount

    suspend fun emit(event: BackendEvent) {
        tryEmit(event)
    }

    fun tryEmit(event: BackendEvent): Boolean =
        if (event.isProgress) _progress.tryEmit(event) else _events.tryEmit(event)

    private val BackendEvent.isProgress: Boolean
        get() = this is BackendEvent.OutboxEvent.ItemProgress ||
            this is BackendEvent.PayloadBundlingEvent.Video.PhaseProgress

    companion object {
        const val PROGRESS_BUFFER_CAPACITY = 64
    }
}
