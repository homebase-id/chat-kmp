package id.homebase.api.client.eventbus

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class EventBus(replay: Int = 1) {
    // Unbounded: a SharedFlow advances at its slowest collector, so any finite buffer lets one
    // stalled collector park or drop events for everyone.
    private val _events =
        MutableSharedFlow<BackendEvent>(replay = replay, extraBufferCapacity = Channel.UNLIMITED)
    val events: SharedFlow<BackendEvent> = _events.asSharedFlow()

    // Lossy: each tick is superseded, and the item's end state arrives losslessly on [events].
    private val _progress = MutableSharedFlow<BackendEvent>(
        extraBufferCapacity = PROGRESS_BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val progress: SharedFlow<BackendEvent> = _progress.asSharedFlow()

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
