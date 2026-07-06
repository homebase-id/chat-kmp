package id.homebase.api.client.eventbus

import co.touchlab.kermit.Logger
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlin.time.TimeSource

class EventBus(
    replay: Int = 1,
    // Buffer headroom for bursts: the whole bus is gated by its slowest collector (a
    // SharedFlow buffer is shared across all subscribers), so when one DB-bound subscriber
    // falls behind, every emitter suspends until it catches up. 64 slots absorb a typical
    // WSPush/outbox burst without parking producers. NOTE: this is headroom, not a fix —
    // a persistently slow subscriber still backs the bus up; it only widens the runway.
    extraBufferCapacity: Int = 64
) {
    private val _events =
        MutableSharedFlow<BackendEvent>(replay = replay, extraBufferCapacity = extraBufferCapacity)
    val events: SharedFlow<BackendEvent> = _events.asSharedFlow()

    /** Live subscriber count. Useful for diagnostics — if `emit` is suspending and
     *  this returns N, then one of those N collectors is more than (replay +
     *  extraBufferCapacity) events behind. */
    val subscriptionCount: StateFlow<Int> = _events.subscriptionCount

    /**
     * Emit, fast-path through tryEmit first so the producer doesn't park on a slow
     * subscriber unless the buffer is actually full. When the buffer IS full, we log
     * the event class + subscriber count + the block duration so a post-mortem can
     * identify which event type is being back-pressured and for how long. Without this
     * instrumentation a stuck subscriber silently freezes every emitter on the bus.
     */
    suspend fun emit(event: BackendEvent) {
        if (_events.tryEmit(event)) return
        val subscribers = _events.subscriptionCount.value
        val eventClass = event::class.simpleName ?: "?"
        Logger.w(tag = "EventBus") {
            "EventBus.emit($eventClass) BUFFER FULL — suspending (subscribers=$subscribers)"
        }
        val start = TimeSource.Monotonic.markNow()
        _events.emit(event)
        val elapsed = start.elapsedNow()
        Logger.w(tag = "EventBus") {
            "EventBus.emit($eventClass) unblocked after $elapsed (subscribers=$subscribers)"
        }
    }

    /** Non-suspending emit. Returns false (and drops the event) when the buffer is full
     *  because a subscriber is slow. Use in paths that must not park on a slow collector —
     *  e.g. the outbox enqueue, which must return immediately so the chat Send button
     *  re-enables regardless of downstream subscriber state. */
    fun tryEmit(event: BackendEvent): Boolean = _events.tryEmit(event)
}
