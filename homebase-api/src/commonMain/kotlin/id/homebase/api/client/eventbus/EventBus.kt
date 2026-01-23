package id.homebase.homebasekmppoc.prototype.lib.eventbus

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class EventBus(
    replay: Int = 1,
    extraBufferCapacity: Int = 10
) {
    private val _events = MutableSharedFlow<id.homebase.homebasekmppoc.prototype.lib.eventbus.BackendEvent>(replay = replay, extraBufferCapacity = extraBufferCapacity)
    val events: SharedFlow<id.homebase.homebasekmppoc.prototype.lib.eventbus.BackendEvent> = _events.asSharedFlow()

    suspend fun emit(event: id.homebase.homebasekmppoc.prototype.lib.eventbus.BackendEvent) = _events.emit(event)
}

val appEventBus =
    _root_ide_package_.id.homebase.homebasekmppoc.prototype.lib.eventbus.EventBus()  // TODO: Make into global singleton for production code